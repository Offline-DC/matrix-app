use std::{collections::{HashMap, HashSet, VecDeque}, num::ParseIntError, ops::{Deref, DerefMut}, sync::{Arc, OnceLock, Weak, atomic::{AtomicBool, AtomicI64, Ordering}, mpsc::SyncSender}, time::{Duration, Instant, SystemTime}};

use evs::{EVSDecoder, EVSEncoder};
use jni::{JNIEnv, JavaVM, objects::{GlobalRef, JClass, JObject, JString, JValue}, sys::{jboolean, jlong}};
use log::{debug, error, info, warn};
use ndk::{audio::{AudioCallbackResult, AudioContentType, AudioDirection, AudioFormat, AudioPerformanceMode, AudioStream, AudioStreamBuilder, AudioUsage}, media::media_codec::{AsyncNotifyCallback, BufferInfo, MediaCodec, MediaCodecDirection, MediaFormat}, native_window::NativeWindow};
use ringbuf::{HeapRb, LocalRb, storage::Heap, traits::{Observer, Producer, RingBuffer}};
use rustpush::avconference::{AVControlCommand, AVSession, AnnexB, AudioParser, AudioSender, ChannelFrame, ChannelMessage, ChannelType, DecoderConfiguration, DeviceOrientation, ImageDescription, TimingTarget, VCControlData, VCGenerateKeyFrame, VideoSender};
use rustpush::facetime::{FTClient, FTMessage};
use tokio::{select, sync::{Mutex, mpsc}};
use ringbuf::traits::Split;
use ringbuf::traits::Consumer;

use crate::rt;

const TIMING_HISTORY_LEN: usize = 200;

#[repr(C)]
#[derive(Debug, Copy, Clone)]
struct Timespec {
    pub tv_sec: i64,
    pub tv_nsec: i64,
}

extern "C" {
    fn clock_gettime(clk_id: i32, tp: *mut Timespec) -> i32;
}

// 1. Swap MONOTONIC (1) for BOOTTIME (7)
const CLOCK_MONOTONIC: i32 = 1;

/// Fetches absolute system uptime in nanoseconds, immune to deep sleep freezes
pub fn get_absolute_monotonic_ns() -> i64 {
    let mut ts = Timespec { tv_sec: 0, tv_nsec: 0 };
    
    // Safety: Passing a valid stack pointer to a thread-safe system call
    let result = unsafe { clock_gettime(CLOCK_MONOTONIC, &mut ts) };
    
    if result != 0 {
        panic!("Fatal: clock_gettime failed to read CLOCK_MONOTONIC.");
    }

    (ts.tv_sec * 1_000_000_000) + ts.tv_nsec
}

fn percentile_timing(
    historical_timing: &LocalRb<Heap<i64>>,
    scratch: &mut [i64; TIMING_HISTORY_LEN],
    percentile: usize,
) -> Option<i64> {
    assert!((1..=100).contains(&percentile));

    let len = historical_timing.peek_slice(scratch);
    if len == 0 {
        return None;
    }

    let timing = &mut scratch[..len];
    let index = ((len * percentile) + 99) / 100 - 1;
    let (_, target, _) = timing.select_nth_unstable(index);
    Some(*target)
}

struct SAudioStream(AudioStream);
unsafe impl Send for SAudioStream {}
unsafe impl Sync for SAudioStream {}
impl Deref for SAudioStream {
    type Target = AudioStream;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
impl DerefMut for SAudioStream {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

struct SMediaCodec(MediaCodec);
unsafe impl Send for SMediaCodec {}
unsafe impl Sync for SMediaCodec {}
impl Deref for SMediaCodec {
    type Target = MediaCodec;
    fn deref(&self) -> &Self::Target {
        &self.0
    }
}
impl DerefMut for SMediaCodec {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.0
    }
}

static JVM: OnceLock<JavaVM> = OnceLock::new();
/// The `FaceTimeEvents` class, resolved once under the app class loader (see below).
static FT_EVENTS_CLASS: OnceLock<GlobalRef> = OnceLock::new();

#[no_mangle]
pub extern "system" fn JNI_OnLoad(vm: JavaVM, _reserved: *mut std::ffi::c_void) -> jni::sys::jint {
    // Cache the FaceTimeEvents class HERE — JNI_OnLoad runs on the Java thread that
    // called System.loadLibrary, whose app class loader can see app classes.
    // `find_class` from a native (attach_current_thread) thread instead uses the
    // system class loader, which can't (ClassNotFoundException). dispatch_ft_message
    // therefore reuses this cached global ref rather than looking the class up.
    if let Ok(mut env) = vm.get_env() {
        match env.find_class("com/offlineinc/dumbdownlauncher/facetime/FaceTimeEvents") {
            Ok(cls) => {
                if let Ok(global) = env.new_global_ref(cls) {
                    let _ = FT_EVENTS_CLASS.set(global);
                }
            }
            Err(e) => warn!("JNI_OnLoad: could not cache FaceTimeEvents class: {e:?}"),
        }
    }
    let _ = JVM.set(vm);
    jni::sys::JNI_VERSION_1_6
}

enum AudioDisplay {
    Aac(AACDisplay),
    Evs(EVSDisplay),
}

#[derive(Default)]
struct ParticipantState {
    process_video: Option<VideoDisplay>,
    process_audio: Option<AudioDisplay>,
    timing: Option<Arc<PlayoutTiming>>,
    
    current_surface: Option<Option<NativeWindow>>,
    imgdesc_cache: Option<Instant>,

    rotation: i32,
}

impl ParticipantState {
    fn handle_command(&mut self, target: RenderCommandTarget, render_send_copy: &SyncSender<RenderControl>, render_state: &FaceTimeNativeState) {
        if self.timing.is_none() {
            // audio timing
            let timing = Arc::new(PlayoutTiming::new(99, 50_000_000));
            render_state.session.register_timing_target(target.participant, timing.clone());
            self.timing = Some(timing);
        }
        match target.r#type {
            ChannelType::H264 | ChannelType::H265 => {
                if let Some(video) = &mut self.process_video {
                    if video.r#type == target.r#type {
                        // make sure that if the codec was recreated we don't send it to the wrong codec
                        if target.id != 0 && video.id != target.id {
                            return;
                        }
                        video.handle_command(target.command);
                        return;
                    }
                    info!("Tearing down existing view for format change!");
                }

                if target.id != 0 {
                    return;
                }

                self.process_video = None; // tear down old decoder first, prevents error configuring new decoder.
                info!("Creating view for!");
                let RenderCommand::Frame(msg) = target.command else { return; };
                let surface = self.current_surface.get_or_insert_with(|| render_state.get_surface_for(target.participant));
                if let Some(surface) = surface {
                    self.process_video = VideoDisplay::new(surface, render_state.display_activity.clone(), &msg, render_state.session.clone(), render_send_copy.clone(), &mut self.imgdesc_cache, self.timing.clone().unwrap(), self.rotation);
                }
            }
            ChannelType::Aac => {
                let process = match &mut self.process_audio {
                    Some(AudioDisplay::Aac(aac)) => aac,
                    _ => {
                        if target.id != 0 {
                            return;
                        }
                        info!("Creating AAC audio for!");
                        self.process_audio = Some(AudioDisplay::Aac(AACDisplay::new(target.participant, render_send_copy.clone(), self.timing.clone().unwrap())));
                        let Some(AudioDisplay::Aac(aac)) = &mut self.process_audio else { unreachable!() };
                        aac
                    }
                };
                if target.id != 0 && process.id != target.id {
                    return;
                }
                process.handle_command(target.command);
            },
            ChannelType::Evs => {
                let item = match &mut self.process_audio {
                    Some(AudioDisplay::Evs(evs)) => evs,
                    _ => {
                        if target.id != 0 {
                            return;
                        }
                        info!("Creating EVS audio for!");
                        self.process_audio = Some(AudioDisplay::Evs(EVSDisplay::new(self.timing.clone().unwrap())));
                        let Some(AudioDisplay::Evs(evs)) = &mut self.process_audio else { unreachable!() };
                        evs
                    }
                };
                let RenderCommand::Frame(frame) = target.command else { panic!() };
                item.handle_frame(frame);
            }
        }
    }

    fn update_rotation(&mut self, rotation: i32) {
        if rotation == self.rotation {
            return;
        }
        self.rotation = rotation;
        self.process_video = None;
    }

    fn update_surface(&mut self, participant: u64, render_state: &FaceTimeNativeState) {
        let Some(current_surface) = &mut self.current_surface else { return };
        let surface = render_state.get_surface_for(participant);
        if let Some(existing) = &mut self.process_video {
            if let Some(surface) = &surface {
                existing.update_surface(surface);
            } else {
                self.process_video = None;
            }
        }
        // it will be re-created on next packet in handler if the surface is Some
        *current_surface = surface;
    }
}

fn decode_hex(s: &str) -> Result<Vec<u8>, ParseIntError> {
    (0..s.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(&s[i..i + 2], 16))
        .collect()
}

struct EVSDisplay {
    min_buf: Arc<AtomicI64>,
    is_frozen: Arc<AtomicBool>,
    pcm_producer: ringbuf::wrap::caching::Caching<Arc<ringbuf::SharedRb<Heap<i16>>>, true, false>,
    timing: Arc<PlayoutTiming>,
    stream: SAudioStream,
    evs_decoder: EVSDecoder,
    log_pacing: u32,
}

impl Drop for EVSDisplay {
    fn drop(&mut self) {
        let _ = self.stream.request_stop();
    }
}

impl EVSDisplay {
    fn new(timing: Arc<PlayoutTiming>) -> Self {
        let evs_decoder = EVSDecoder::new();

        let rb = HeapRb::<i16>::new(20000 /* / 32000 */);
        let (pcm_producer, mut pcm_consumer) = rb.split();
        let minimum_buffer = Arc::new(AtomicI64::new(100_000_000 * 4 / 125_000));
        let is_frozen = Arc::new(AtomicBool::new(true));
        let is_frozen_2 = is_frozen.clone();
        let min_buf = minimum_buffer.clone();
        let stream = SAudioStream(AudioStreamBuilder::new().unwrap()
            .sample_rate(32_000)
            .channel_count(1)
            .format(AudioFormat::PCM_I16)
            // Mark this as a voice-call stream so it follows in-call routing
            // (earpiece vs. speaker via setCommunicationDevice in MODE_IN_COMMUNICATION).
            // Without this, AAudio defaults to MEDIA/MUSIC, which only ever plays out
            // the speaker and ignores the earpiece.
            .usage(AudioUsage::VoiceCommunication)
            .content_type(AudioContentType::Speech)
            .performance_mode(AudioPerformanceMode::LowLatency)
            .data_callback(Box::new(move |stream, out, frames| {
                let output_target = out as *mut i16;
                let needed = frames as usize; // 2 bytes per frame;
                let target_goal = unsafe { std::slice::from_raw_parts_mut(output_target, needed) };

                let mut occupied = pcm_consumer.occupied_len();
                // info!("EVS Asking for {frames} frames available {occupied}");

                if occupied < needed {
                    warn!("EVS Overran!");
                    is_frozen.store(true, Ordering::Relaxed);
                } else if is_frozen.load(Ordering::Relaxed) && occupied >= minimum_buffer.load(Ordering::Relaxed) as usize {
                    is_frozen.store(false, Ordering::Relaxed);
                } else {
                    while occupied >= needed + 3200 && occupied > minimum_buffer.load(Ordering::Relaxed) as usize + 3200 {
                        warn!("Dropping samples!");
                        let mut buf = [0i16; 3200];
                        pcm_consumer.pop_slice(&mut buf);
                        occupied -= 3200;
                    }
                }

                if !is_frozen.load(Ordering::Relaxed) {
                    pcm_consumer.pop_slice(target_goal);
                } else {
                    target_goal.fill(0);
                }
                AudioCallbackResult::Continue
            }))
            .open_stream().unwrap());

        stream.request_start().unwrap();

        Self {
            min_buf,
            is_frozen: is_frozen_2,
            pcm_producer,
            timing,
            stream,
            evs_decoder,
            log_pacing: 0,
        }
    }

    fn handle_frame(&mut self, frame: ChannelMessage) {
        for i in 0..frame.prev_dropped {
            let result = self.evs_decoder.missing();
            self.pcm_producer.push_slice(&result);
        }

        let scale = frame.timestamp as i64 * 125 / 3;

        let ChannelFrame::Sample(mut out) = frame.frame else { panic!("Audio not sample??") };
        // info!("EVS Proccesing {} {}", encode_hex(&out), out.len());
        // TODO offload to a different thread at some point.
        let result = self.evs_decoder.decode(&mut out);

        let presentation_ns = scale * 1000;
        let target_time = self.timing.estimate_ns(presentation_ns);
        let intended_delay = (target_time - get_absolute_monotonic_ns())
            .clamp(50_000_000, 750_000_000);
        self.min_buf.store(intended_delay * 4 / 125_000, Ordering::Relaxed);

        let effective_playout = get_absolute_monotonic_ns() + if self.is_frozen.load(Ordering::Relaxed) {
            intended_delay
        } else {
            self.pcm_producer.occupied_len() as i64 * 125_000 / 4
        };
        let track_time = effective_playout - presentation_ns;
        self.timing.current_target.store(track_time, Ordering::Relaxed);

        
        self.log_pacing = self.log_pacing.wrapping_add(1);
        if self.log_pacing % 50 == 0 {
            info!("Got EVS buffer; Calibrating delay to {}", intended_delay / 1_000_000);
        }

        self.pcm_producer.push_slice(&result);
    }
}

struct AACDisplay {
    id: u64,
    decoder: SMediaCodec,
    extra_frames: VecDeque<ChannelMessage>,
    extra_buffers: VecDeque<usize>,
    timing: Arc<PlayoutTiming>,
    stream: SAudioStream,
    min_buf: Arc<AtomicI64>,
    is_frozen: Arc<AtomicBool>,
    pcm_producer: ringbuf::wrap::caching::Caching<Arc<ringbuf::SharedRb<Heap<i16>>>, true, false>,
    log_pacing: u32,
}

impl Drop for AACDisplay {
    fn drop(&mut self) {
        let _ = self.decoder.stop();
        let _ = self.stream.request_stop();
    }
}

impl AACDisplay {
    fn new(participant: u64, render_sender: SyncSender<RenderControl>, timing: Arc<PlayoutTiming>) -> Self {
        let mut format = MediaFormat::new();
        let mime = "audio/mp4a-latm";
        format.set_str("mime", mime);
        format.set_i32("sample-rate", 24_000);
        format.set_i32("channel-count", 1);

        format.set_i32("aac-profile", 39 /* ELD */);

        // get magic cookie after SoundDec initialization
        format.set_buffer("csd-0", &decode_hex("f8ec312aa08c00").unwrap());
        
        let mut decoder = SMediaCodec(MediaCodec::from_decoder_type(mime).expect(&format!("No decoder for {mime}??")));
        decoder.configure(&format, None, MediaCodecDirection::Decoder).unwrap();


        let is_frozen = Arc::new(AtomicBool::new(true));
        let is_frozen_2 = is_frozen.clone();
        let rb = HeapRb::<i16>::new(18000 /* /24000 */);
        let (pcm_producer, mut pcm_consumer) = rb.split();
        let minimum_buffer = Arc::new(AtomicI64::new(100_000_000 * 3 / 125_000));
        let min_buf = minimum_buffer.clone();
        let stream = SAudioStream(AudioStreamBuilder::new().unwrap()
            .sample_rate(24_000)
            .channel_count(1)
            .format(AudioFormat::PCM_I16)
            // Voice-call stream: follow in-call earpiece/speaker routing (see the EVS
            // path above). Default MEDIA/MUSIC would only play out the speaker.
            .usage(AudioUsage::VoiceCommunication)
            .content_type(AudioContentType::Speech)
            .performance_mode(AudioPerformanceMode::LowLatency)
            .data_callback(Box::new(move |stream, out, frames| {
                let output_target = out as *mut i16;
                let needed = frames as usize; // 2 bytes per frame;
                let target_goal = unsafe { std::slice::from_raw_parts_mut(output_target, needed) };

                let mut occupied = pcm_consumer.occupied_len();
                // info!("AAC Asking for {frames} frames available {occupied}");

                if occupied < needed {
                    warn!("AAC Overran!");
                    is_frozen.store(true, Ordering::Relaxed);
                } else if is_frozen.load(Ordering::Relaxed) && occupied >= minimum_buffer.load(Ordering::Relaxed) as usize {
                    is_frozen.store(false, Ordering::Relaxed);
                } else {
                    while occupied >= needed + 2400 && occupied > minimum_buffer.load(Ordering::Relaxed) as usize + 2400 {
                        warn!("Dropping samples!");
                        let mut buf = [0i16; 2400];
                        pcm_consumer.pop_slice(&mut buf);
                        occupied -= 2400;
                    }
                }

                if !is_frozen.load(Ordering::Relaxed) {
                    pcm_consumer.pop_slice(target_goal);
                } else {
                    target_goal.fill(0);
                }
                AudioCallbackResult::Continue
            }))
            .open_stream().unwrap());

        stream.request_start().unwrap();


        let input_sender = render_sender.clone();

        let id: u64 = rand::random();

        decoder.set_async_notify_callback(Some(AsyncNotifyCallback {
            on_input_available: Some(Box::new(move |index| {
                let _ = input_sender.try_send(RenderControl::Participant(RenderCommandTarget {
                    participant,
                    r#type: ChannelType::Aac,
                    id,
                    command: RenderCommand::Buffer(index),
                }));
            })),
            on_output_available: Some(Box::new(move |item, meta| {
                let _ = render_sender.try_send(RenderControl::Participant(RenderCommandTarget {
                    participant,
                    r#type: ChannelType::Aac,
                    id,
                    command: RenderCommand::Output(item, meta.clone()),
                }));
            })),
            on_format_changed: Some(Box::new(|item| {
                info!("AAC Format changed {item}");
            })),
            on_error: Some(Box::new(|error, action, etc| {
                info!("AAC Error {error} {action:?} {etc:?}");
            })),
        })).unwrap();
        decoder.start().unwrap();
        
        Self {
            id,
            decoder,
            extra_buffers: VecDeque::new(),
            extra_frames: VecDeque::new(),
            timing,
            stream,
            min_buf,
            pcm_producer,
            log_pacing: 0,
            is_frozen: is_frozen_2,
        }
    }

    fn handle_media(&mut self, buffer: usize, frame: ChannelMessage) {
        // info!("AAC Proccesing {frame:?}");

        let buf = self.decoder.input_buffer(buffer).unwrap();
        let ChannelFrame::Sample(out) = frame.frame else { panic!("Audio not sample??") };

        let len = out.len();

        buf.iter_mut()
            .zip(out.into_iter())
            .for_each(|(slot, byte)| {
                slot.write(byte);
            });
        
        let scale = frame.timestamp as u64 * 125 / 3;
        self.decoder.queue_input_buffer_by_index(buffer, 0, len, scale, 0).unwrap();
    }

    fn push_buffer(&mut self, buffer: usize) {
        if let Some(frame) = self.extra_frames.pop_front() {
            self.handle_media(buffer, frame);
        } else {
            self.extra_buffers.push_back(buffer);
            return;
        }
    }

    fn push_frame(&mut self, frame: ChannelMessage) {
        if let Some(buffer) = self.extra_buffers.pop_front() {
            self.handle_media(buffer, frame)
        } else {
            self.extra_frames.push_back(frame);
            if self.extra_frames.len() > 16 {
                info!("Got frame, waiting on buf!a");
            }
            return;
        }
    }

    fn handle_command(&mut self, command: RenderCommand) {
        match command {
            RenderCommand::Buffer(buffer) => self.push_buffer(buffer),
            RenderCommand::Frame(frame) => self.push_frame(frame),
            RenderCommand::Output(idx, buf) => self.handle_output(idx, buf),
        }
    }

    fn handle_output(&mut self, idx: usize, info: BufferInfo) {
        if (info.flags() & 2 /* AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG */) != 0 {
            self.decoder.release_output_buffer_by_index(idx, false).unwrap();
            return;
        }

        let presentation_ns = info.presentation_time_us() as i64 * 1000;
        let target_time = self.timing.estimate_ns(presentation_ns);

        let intended_delay = (target_time - get_absolute_monotonic_ns())
            .clamp(50_000_000, 750_000_000);
        self.min_buf.store(intended_delay * 3 / 125_000, Ordering::Relaxed);

        let effective_playout = get_absolute_monotonic_ns() + if self.is_frozen.load(Ordering::Relaxed) {
            intended_delay
        } else {
            self.pcm_producer.occupied_len() as i64 * 125_000 / 3
        };
        let track_time = effective_playout - presentation_ns;
        self.timing.current_target.store(track_time, Ordering::Relaxed);

        self.log_pacing = self.log_pacing.wrapping_add(1);
        if self.log_pacing % 50 == 0 {
            info!("Got AAC buffer; Calibrating delay to {}", intended_delay / 1_000_000);
        }
        
        let output_buffer = self.decoder.output_buffer(idx).expect("no output buf!");
        let buf = &output_buffer[info.offset() as usize..(info.offset() + info.size()) as usize];
        let buf_as_i16 = unsafe { std::slice::from_raw_parts(buf.as_ptr() as *const i16, buf.len() / 2) };
        self.pcm_producer.push_slice(buf_as_i16);

        self.decoder.release_output_buffer_by_index(idx, false).unwrap();
    }
}

struct PlayoutTimingInner {
    historical_timing: LocalRb<Heap<i64>>,
    timing_scratch: [i64; TIMING_HISTORY_LEN],
}

struct PlayoutTiming {
    inner: std::sync::Mutex<PlayoutTimingInner>,
    percentile: usize,
    extra_ns: i64,
    current_target: AtomicI64,
    smallest_target: AtomicI64,
}

impl PlayoutTiming {
    fn new(percentile: usize, extra_ns: i64) -> Self {
        Self {
            inner: std::sync::Mutex::new(PlayoutTimingInner {
                historical_timing: LocalRb::new(TIMING_HISTORY_LEN),
                timing_scratch: [0; TIMING_HISTORY_LEN],
            }),
            percentile,
            extra_ns,
            current_target: AtomicI64::new(0),
            smallest_target: AtomicI64::new(0),
        }
    }

    fn estimate_ns(&self, presentation_ns: i64) -> i64 {
        let lock = &mut *self.inner.lock().unwrap();
        let current_ns = get_absolute_monotonic_ns();
        let relative = current_ns - presentation_ns;
        lock.historical_timing.push_overwrite(relative);

        let target_ref = percentile_timing(&lock.historical_timing, &mut lock.timing_scratch, self.percentile).unwrap_or(relative);
        // self.current_target.store(target_ref + self.extra_ns, Ordering::Relaxed);
        self.smallest_target.store(*lock.historical_timing.iter().min().unwrap(), Ordering::Relaxed);

        let target_ns = target_ref + presentation_ns + self.extra_ns;
        let delay_ns = (target_ns - current_ns).min(750_000_000); // reject over 750ms delay, we can't have this
        current_ns + delay_ns
    }

    fn score_ns(&self, presentation_ns: i64) -> (i64, i64) {
        let current_ns = get_absolute_monotonic_ns();
        let target_ref = self.current_target.load(Ordering::Relaxed);
        let target_ns = target_ref + presentation_ns;
        let delay_ns = (target_ns - current_ns).min(750_000_000); // reject over 750ms delay, we can't have this
        (current_ns + delay_ns, delay_ns)
    }
}

impl TimingTarget for PlayoutTiming {
    fn presentation_for(&self, timestamp: u32) -> tokio::time::Instant {
        let presentation_ns = timestamp as i64 * 125 / 3 * 1000;
        let presentation_monotonic = self.current_target.load(Ordering::Relaxed) + presentation_ns;
        let relative = (presentation_monotonic - get_absolute_monotonic_ns()).min(750_000_000);
        if relative > 0 {
            tokio::time::Instant::now() + Duration::from_nanos(relative.unsigned_abs())
        } else {
            tokio::time::Instant::now() - Duration::from_nanos(relative.unsigned_abs())
        }
    }
}

struct VideoDisplay {
    id: u64,
    r#type: ChannelType,
    decoder: SMediaCodec,
    extra_frames: VecDeque<ChannelMessage>,
    extra_buffers: VecDeque<usize>,
    is_frozen: bool,
    frozen_at: Instant,
    current_rvra: Option<Vec<u8>>,
    frozen_loss: u32,
    frozen_stream: u32,
    timing: Arc<PlayoutTiming>,
    ft: Arc<AVSession>,
    log_pacing: u32,
}

impl Drop for VideoDisplay {
    fn drop(&mut self) {
        let _ = self.decoder.stop();
    }
}

impl VideoDisplay {
    fn new(surface: &NativeWindow, activity: GlobalRef, msg: &ChannelMessage, ft: Arc<AVSession>, render_sender: SyncSender<RenderControl>, imgdesc_cache: &mut Option<Instant>, timing: Arc<PlayoutTiming>, rotation_degrees: i32) -> Option<VideoDisplay> {
        let r#type = msg.r#type;
        let mime = match &r#type {
            ChannelType::H264 => "video/avc",
            ChannelType::H265 => "video/hevc",
            _ => return None
        };

        let ChannelFrame::Configuration(desc) = &msg.frame else {
            if imgdesc_cache.as_ref().map(|i| i.elapsed() > Duration::from_secs(3)).unwrap_or(true) {
                let ft_copy = ft.clone();
                let participant = msg.participant;
                let stream_id = msg.stream_id;
                info!("Requesting IDR for imagedesc");
                // request IDR
                rt().spawn(async move {
                    if let Err(e) = ft_copy.send_control_message(participant, VCControlData::GenerateKeyFrame(VCGenerateKeyFrame {
                        stream_id,
                        stream_group_id: u32::from_be_bytes(*b"came"),
                        fir_type: 0, // (seems to need 0 to request)
                    })).await {
                        warn!("Failed to send control message {e}");
                    }
                });

                *imgdesc_cache = Some(Instant::now());
            }

            warn!("Initial not imagedesc, skipping!");
            return None
        };

        let id: u64 = rand::random();

        let dimens = desc.get_dimens();
        info!("Got dimensions {dimens:?}");
        let mut format = MediaFormat::new();
        format.set_str("mime", mime);
        format.set_i32("latency", 0);
        format.set_i32("width", dimens.0);
        format.set_i32("height", dimens.1);
        format.set_i32("rotation-degrees", rotation_degrees);
        format.set_buffer("csd-0", &desc.annex_b());
        
        let input_sender = render_sender.clone();
        let participant = msg.participant;

        let mut decoder = SMediaCodec(MediaCodec::from_decoder_type(mime).expect(&format!("No decoder for {mime}??")));
        decoder.configure(&format, Some(surface), MediaCodecDirection::Decoder).unwrap();
        decoder.set_async_notify_callback(Some(AsyncNotifyCallback {
            on_input_available: Some(Box::new(move |index| {
                let _ = input_sender.try_send(RenderControl::Participant(RenderCommandTarget {
                    participant,
                    r#type,
                    id,
                    command: RenderCommand::Buffer(index),
                }));
            })),
            on_output_available: Some(Box::new(move |item, meta| {
                let _ = render_sender.try_send(RenderControl::Participant(RenderCommandTarget {
                    participant,
                    r#type,
                    id,
                    command: RenderCommand::Output(item, meta.clone()),
                }));
            })),
            on_format_changed: Some(Box::new(move |item| {
                info!("Format changed {item}");
                let vm = JVM.get().expect("JVM not initialized");

                let mut attach = vm.attach_current_thread().unwrap();
                attach.call_method(&activity, "setParticipantVideoSize", "(JII)V", &[
                    JValue::Long(participant as i64), 
                    JValue::Int(item.i32("width").unwrap_or_default()),
                    JValue::Int(item.i32("height").unwrap_or_default()),
                ]).unwrap();
            })),
            on_error: Some(Box::new(|error, action, etc| {
                info!("Error {error} {action:?} {etc:?}");
            })),
        })).unwrap();
        decoder.start().unwrap();

        Some(VideoDisplay {
            id,
            r#type,
            decoder,
            extra_buffers: VecDeque::new(),
            extra_frames: VecDeque::new(),
            is_frozen: false,
            frozen_at: Instant::now(),
            frozen_stream: 0,
            current_rvra: Some(vec![0, 0]),
            frozen_loss: 0,
            timing,
            ft,
            log_pacing: 0,
        })
    }

    fn handle_media(&mut self, buffer: usize, frame: ChannelMessage) {
        // info!("VIDEO Proccesing {frame:?}");

        let request_idr = || {
            let ft_copy = self.ft.clone();
            // request IDR
            // let is_rvra = frame.metadata.get("RVRA1").is_some();
            let stream_id = frame.stream_id;
            info!("requesting stream from {}", stream_id);
            rt().spawn(async move {
                if let Err(e) = ft_copy.send_control_message(frame.participant, VCControlData::GenerateKeyFrame(VCGenerateKeyFrame {
                    stream_id,
                    stream_group_id: u32::from_be_bytes(*b"came"),
                    // non-groups, if !is_rvra { 1 } else { 2 }
                    // 4 works for groups, requests immediate keyframe
                    fir_type: 4,
                })).await {
                    warn!("Failed to send control message {e}");
                }
            });
        };

        self.extra_buffers.push_front(buffer);

        if frame.r#type != self.r#type {
            warn!("ignoring wrong video type!");
            return;
        }

        let is_u1 = false;

        let buf = self.decoder.input_buffer(buffer).unwrap();
        let out = match frame.frame {
            ChannelFrame::Configuration(image) => image.annex_b(),
            ChannelFrame::Sample(sample) => {
                if self.is_frozen {
                    // make sure we have a keyframe, or else ignore this
                    let is_idr = AnnexB::new(&sample).any(|sample| {
                        match frame.r#type {
                            ChannelType::H264 => {
                                let nal_type = sample[0] & 0x1f;
                                matches!(nal_type, 5 | 7 /* SPS */ | 8 /* PPS */)
                            },
                            ChannelType::H265 => {
                                let nal_type = (sample[0] >> 1) & 0x3f;
                                matches!(nal_type, 19 | 20 | 32 /* VPS */ | 33 /* SPS */ | 34 /* PPS */)
                            },
                            _ => panic!("what!!")
                        }
                    });
                    if !is_idr {
                        self.frozen_loss += frame.prev_dropped as u32;
                        if (self.frozen_at.elapsed() > Duration::from_secs(3) && self.frozen_loss != 0) || self.frozen_stream != frame.stream_id {
                            warn!("Re-requesting IDR {} {}", self.frozen_at.elapsed() > Duration::from_secs(3) && self.frozen_loss != 0, self.frozen_stream != frame.stream_id);
                            self.frozen_loss = 0;
                            self.frozen_at = Instant::now();
                            self.frozen_stream = frame.stream_id;
                            request_idr();
                        }
                        // warn!("Discarding frame because frozen! {} {}", self.frozen_loss, self.frozen_at.elapsed().as_secs());
                        return;
                    } else {
                        info!("Releasing frame for keyframe!");
                    }
                } else if frame.prev_dropped > 0 || (frame.metadata.get("RVRA1") != self.current_rvra.as_ref() && is_u1) {
                    self.is_frozen = true;
                    self.frozen_loss = 0;
                    self.frozen_at = Instant::now();
                    self.frozen_stream = frame.stream_id;
                    warn!("Freezing frame for PACKET: {} RVRA: {}", frame.prev_dropped > 0, (frame.metadata.get("RVRA1") != self.current_rvra.as_ref() && is_u1));
                    
                    request_idr();
                    return;
                }
                self.current_rvra = frame.metadata.get("RVRA1").cloned();
                self.is_frozen = false;

                sample
            },
        };

        let len = out.len();

        self.extra_buffers.pop_front();

        buf.iter_mut()
            .zip(out.into_iter())
            .for_each(|(slot, byte)| {
                slot.write(byte);
            });
        
        let scale = frame.timestamp as u64 * 125 / 3;
        self.decoder.queue_input_buffer_by_index(buffer, 0, len, scale, 0).unwrap();
    }

    fn update_surface(&mut self, surface: &NativeWindow) {
        info!("Got updated surface!");
        self.decoder.set_output_surface(surface).unwrap();
    }

    fn push_buffer(&mut self, buffer: usize) {
        if let Some(frame) = self.extra_frames.pop_front() {
            self.handle_media(buffer, frame);
        } else {
            self.extra_buffers.push_back(buffer);
            return;
        }
    }

    fn push_frame(&mut self, frame: ChannelMessage) {
        if let Some(buffer) = self.extra_buffers.pop_front() {
            self.handle_media(buffer, frame)
        } else {
            self.extra_frames.push_back(frame);
            if self.extra_frames.len() > 16 {
                info!("Got frame, waiting on buf!");
            }
            return;
        }
    }

    fn handle_output(&mut self, idx: usize, buf: BufferInfo) {
        if (buf.flags() & 2 /* AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG */) != 0 {
            self.decoder.release_output_buffer_by_index(idx, false).unwrap();
            return;
        }

        let (target_time, _) = self.timing.score_ns(buf.presentation_time_us() as i64 * 1000);

        self.log_pacing = self.log_pacing.wrapping_add(1);
        if self.log_pacing % 30 == 0 {
            info!(
                "Got output frame; Calibrating delay to {} ms", 
                (target_time - get_absolute_monotonic_ns()) / 1000000
            );
        }
        self.decoder.release_output_buffer_at_time_by_index(idx, target_time).unwrap();
    }

    fn handle_command(&mut self, command: RenderCommand) {
        match command {
            RenderCommand::Buffer(buffer) => self.push_buffer(buffer),
            RenderCommand::Frame(frame) => self.push_frame(frame),
            RenderCommand::Output(idx, buf) => self.handle_output(idx, buf),
        }
    }
}

struct AudioCapture {
    coder: EVSEncoder,
    timestamp: i64,
    stream: SAudioStream,
    muted: bool,
    unmute_resync: bool,
    pcm_consumer: ringbuf::wrap::caching::Caching<Arc<ringbuf::SharedRb<Heap<i16>>>, false, true>,
    sender: HashMap<Option<u32>, AudioSender>,
    rtp_base_time: SystemTime,
    next_target: Instant,
}

impl Drop for AudioCapture {
    fn drop(&mut self) {
        let _ = self.stream.request_stop();
    }
}

impl AudioCapture {
    fn new(rtp_base_time: SystemTime, wake_sender: SyncSender<()>) -> Self {
        let rb = HeapRb::<i16>::new(9600 /* 300ms */);
        let (mut pcm_producer, pcm_consumer) = rb.split();
        let stream = SAudioStream(AudioStreamBuilder::new().unwrap()
            .direction(AudioDirection::Input)
            .sample_rate(32_000)
            .channel_count(1)
            .format(AudioFormat::PCM_I16)
            .performance_mode(AudioPerformanceMode::LowLatency)
            .data_callback(Box::new(move |stream, out, frames| {
                let output_target = out as *mut i16;
                let needed = frames as usize; // 2 bytes per frame;
                let target_goal = unsafe { std::slice::from_raw_parts(output_target, needed) };

                pcm_producer.push_slice(target_goal);
                if pcm_producer.occupied_len() >= 640 {
                    let _ = wake_sender.try_send(());
                }
                AudioCallbackResult::Continue
            }))
            .open_stream().unwrap());

        Self {
            coder: EVSEncoder::new(),
            timestamp: 0,
            stream,
            pcm_consumer,
            muted: false,
            unmute_resync: false,
            sender: HashMap::new(),
            rtp_base_time,
            next_target: Instant::now(),
        }
    }

    fn set_muted(&mut self, muted: bool) {
        if self.muted == muted { return }
        self.muted = muted;

        if muted {
            let _ = self.stream.request_stop();
        } else {
            self.start();
            self.unmute_resync = true;
        }
    }

    fn start(&mut self) {
        info!("Starting audio");
        self.pcm_consumer.clear();
        let _ = self.stream.request_start();
    }

    fn timeout(&mut self) -> Duration {
        if !self.muted && !self.unmute_resync {
            return Duration::from_millis(50);
        }

        self.next_target.duration_since(Instant::now())
    }

    fn send_frame(&mut self, result: [i16; 640]) {
        if self.timestamp == 0 {
            self.timestamp = (self.rtp_base_time.elapsed().unwrap().as_micros() as i64) * 3 / 125 - 480;
        }

        let frame = self.coder.encode(result);
        let padded_frame = [
            &[frame.len() as u8],
            &frame[..]
        ].concat();

        for sender in self.sender.values_mut() {
            if let Err(e) = sender.send_audio_frame(&padded_frame, self.timestamp as u32) {
                warn!("Failed to send audio frame {e}!");
            }
        }
        self.timestamp += 480; // 20 ms
    }

    fn process_consume(&mut self) {
        if self.muted || self.unmute_resync {
            if Instant::now() >= self.next_target {
                self.send_frame([0i16; 32_000 / 50]);
                self.next_target += Duration::from_millis(20);
                if self.unmute_resync && !self.muted && self.pcm_consumer.occupied_len() > 0 {
                    self.pcm_consumer.clear();
                    self.unmute_resync = false;
                }
            }
        } else {
            while self.pcm_consumer.occupied_len() >= 640 {
                let mut result = [0i16; 32_000 / 50];
                self.pcm_consumer.pop_slice(&mut result);
                self.send_frame(result);
                self.next_target = Instant::now() + Duration::from_millis(20);
            }
        }
    }
}

struct CameraCapture {
    encoder: SMediaCodec,
    sender: HashMap<Option<u32>, VideoSender>,
    start_shift: Option<i64>,
    codec_config: Vec<u8>,
    wants_config: bool,
    last_key_frame: SystemTime,
    rtp_base_time: SystemTime,
    is_u1: bool,
}

impl Drop for CameraCapture {
    fn drop(&mut self) {
        let _ = self.encoder.stop();
    }
}

impl CameraCapture {
    fn new(self_sender: SyncSender<CameraData>, frontend: GlobalRef, rtp_base_time: SystemTime) -> Self {
        info!("Encoder started");
        let mut encoder = {
            let mut format = MediaFormat::new();
            format.set_str("mime", "video/hevc");
            // GROUP FIX
            format.set_i32("width", 1920);
            format.set_i32("height", 1080);
            format.set_i32("bitrate", 597_000);
            format.set_i32("frame-rate", 30);

            // format.set_i32("bitrate", 45_000);
            // format.set_i32("frame-rate", 15);
            
            format.set_i32("bitrate-mode", 2);

            format.set_i32("i-frame-interval", 20);

            let encoder = SMediaCodec(MediaCodec::from_encoder_type("video/hevc").expect("No encoder for HEVC??"));
            encoder.configure(&format, None, MediaCodecDirection::Encoder).unwrap();
            encoder
        };

        let input_surface = encoder.create_input_surface().unwrap();
        encoder.set_async_notify_callback(Some(AsyncNotifyCallback {
            on_input_available: Some(Box::new(|index| {
                // irrelevant; from camera
            })),
            on_output_available: Some(Box::new(move |item, meta| {
                let _ = self_sender.try_send(CameraData::Buffer(item, meta.clone()));
            })),
            on_format_changed: Some(Box::new(|item| {
                info!("Encoder Format changed {item}");
            })),
            on_error: Some(Box::new(|error, action, etc| {
                info!("Encoder Error {error} {action:?} {etc:?}");
            })),
        })).unwrap();

        {
            let vm = JVM.get().expect("JVM not initialized");
            let mut attach = vm.attach_current_thread().unwrap();
            let jvm_surface = unsafe { JObject::from_raw(input_surface.to_surface(attach.get_raw())) };
            attach.call_method(&frontend, "cameraEncodeSurface", "(Landroid/view/Surface;)V", &[JValue::Object(&jvm_surface)]).unwrap();
        }

        info!("Started camera capture!");

        Self {
            encoder,
            sender: HashMap::new(),
            start_shift: None,
            codec_config: vec![],
            wants_config: true,
            last_key_frame: SystemTime::UNIX_EPOCH,
            rtp_base_time,
            is_u1: false,
        }
    }

    fn set_bitrate(&self, bitrate: usize) {
        info!("Setting bitrate to {bitrate}!");
        let mut format = MediaFormat::new();
        format.set_i32("video-bitrate", bitrate as i32);
        self.encoder.set_parameters(format).unwrap();
    }

    fn handle_buffer(&mut self, recv: usize, info: BufferInfo) {
        let csd_buf = self.encoder.output_buffer(recv).unwrap();
        let buf = &csd_buf[info.offset() as usize..(info.offset() + info.size()) as usize];
        if (info.flags() & 2 /* AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG */) != 0 {
            self.codec_config = buf.to_vec();
            info!("Got config!");
            self.encoder.release_output_buffer_by_index(recv, false).unwrap();
            return;
        }

        if (info.flags() & 1 /* AMEDIACODEC_BUFFER_FLAG_KEY_FRAME */) != 0 && self.wants_config {
            let config = DecoderConfiguration::from_annex_b_hevc(&self.codec_config, !self.is_u1);
            // timestamp isn't used at this time.
            for sender in self.sender.values_mut() {
                if let Err(e) = sender.send_video_frame(ChannelFrame::Configuration(config.clone()), 0) {
                    warn!("Failed to send video initial frame {e}!");
                }
            }
            self.wants_config = false;
        }

        let time = self.start_shift.get_or_insert_with(|| info.presentation_time_us() as i64 - self.rtp_base_time.elapsed().unwrap().as_micros() as i64);

        let timing = (info.presentation_time_us() as i64 - *time) * 3 / 125;
        for sender in self.sender.values_mut() {
            if let Err(e) = sender.send_video_frame(ChannelFrame::Sample(buf.to_vec()), timing as u32) {
                warn!("Failed to send video frame {e}!");
            }
        }
        self.encoder.release_output_buffer_by_index(recv, false).unwrap();
    }

    fn generate_key_frame(&mut self) {
        let time = self.last_key_frame.elapsed().unwrap();
        if time < Duration::from_millis(3000) {
            warn!("Ignoring keyframe request for recent keyframe!");
            return;
        }
        info!("Generating a keyframe on request!");
        self.wants_config = true;
        self.last_key_frame = SystemTime::now();
        let mut format = MediaFormat::new();
        format.set_i32("request-sync", 0);
        self.encoder.set_parameters(format).unwrap();
    }
}

enum RenderCommand {
    Frame(ChannelMessage),
    Buffer(usize),
    Output(usize, BufferInfo),
}

struct RenderCommandTarget {
    participant: u64,
    r#type: ChannelType,
    id: u64,
    command: RenderCommand
}

enum CameraData {
    GenerateKeyFrame,
    Buffer(usize, BufferInfo),
    EnableStreams(Vec<Option<u32>>),
    SetVideoBitrate(usize),
    Finish,
}

enum MicData {
    EnableStreams(Vec<Option<u32>>),
    Enabled(bool),
    Finish,
}

enum RenderControl {
    Participant(RenderCommandTarget),
    ActiveParticipants(HashSet<u64>),
    UpdateSurfaces,
    UpdateOrientation(u64, DeviceOrientation),
    Finish,
}

struct FaceTimeNativeState {
    session: Arc<AVSession>,
    ft: Arc<FTClient>,
    display_activity: GlobalRef,
    camera_data: std::sync::mpsc::SyncSender<CameraData>,
    mic_data: std::sync::mpsc::SyncSender<MicData>,
    mic_control: std::sync::mpsc::SyncSender<()>,
    render_send: std::sync::mpsc::SyncSender<RenderControl>,
    uuid: String,
}

impl Drop for FaceTimeNativeState {
    fn drop(&mut self) {
        info!("dropping FT native state");
        let _ = self.mic_data.try_send(MicData::Finish);
        let _ = self.mic_control.try_send(());
        let _ = self.camera_data.try_send(CameraData::Finish);
        let _ = self.render_send.try_send(RenderControl::Finish);
    }
}

impl FaceTimeNativeState {
    async fn new(ft: Arc<FTClient>, uuid: &str, display_activity: GlobalRef) -> (Arc<Self>, bool) {
        info!("FaceTime NEW");
        let mut lock = ft.state.write().await;
        let session = lock.sessions.get_mut(uuid).expect("FT Found no session!");

        if session.connection.is_none() {
            ft.connect_to_relay(session, &[]).await.unwrap();
            ft.join(session, false).await.unwrap();
        }
        let is_video = session.is_video;
        let session = session.connection.clone().expect("No session!!");
        drop(lock);

        info!("FaceTime connected: {uuid}");
        
        let (mic_data, mic_recv) = std::sync::mpsc::sync_channel::<MicData>(1024);
        let (mic_control, mic_trigger_recv) = std::sync::mpsc::sync_channel::<()>(1024);
        let (render_send, render_recv) = std::sync::mpsc::sync_channel::<RenderControl>(1024);
        let (send_control, recv_control) = std::sync::mpsc::sync_channel(1024);
        let state = Arc::new(Self {
            session,
            ft,
            display_activity,
            camera_data: send_control,
            mic_data,
            mic_control,
            uuid: uuid.to_string(),
            render_send: render_send.clone(),
        });

        let render_send_copy = render_send.clone();
        let render_state = Arc::downgrade(&state);
        std::thread::spawn(move || {
            let mut participants: HashMap<u64, ParticipantState> = HashMap::new();
            let mut participant_remove: HashMap<u64, Instant> = HashMap::new();
            loop {
                let target = match render_recv.recv() {
                    Ok(target) => target,
                    Err(std::sync::mpsc::RecvError) => break,
                };

                match target {
                    RenderControl::ActiveParticipants(active) => {
                        participants.retain(|i, _| {
                            let exists = active.contains(i);
                            if !exists {
                                participant_remove.insert(*i, Instant::now());
                            }
                            exists
                        });
                    },
                    RenderControl::Participant(p) => {
                        if !participants.contains_key(&p.participant) {
                            if let Some(instant) = participant_remove.get(&p.participant) {
                                if instant.elapsed() < Duration::from_secs(1) {
                                    continue;
                                } else {
                                    participant_remove.remove(&p.participant);
                                }
                            }
                        }
                        let Some(render_state) = render_state.upgrade() else {
                            break;
                        };
                        let entry = participants.entry(p.participant).or_default();
                        entry.handle_command(p, &render_send_copy, &render_state);
                    },
                    RenderControl::UpdateSurfaces => {
                        info!("Updating surfaces!");
                        let Some(render_state) = render_state.upgrade() else {
                            break;
                        };
                        for (participant, state) in &mut participants {
                            state.update_surface(*participant, &render_state);
                        }
                    },
                    RenderControl::UpdateOrientation(participant, orientation) => {
                        if !participants.contains_key(&participant) {
                            if let Some(instant) = participant_remove.get(&participant) {
                                if instant.elapsed() < Duration::from_secs(1) {
                                    continue;
                                } else {
                                    participant_remove.remove(&participant);
                                }
                            }
                        }
                        let entry = participants.entry(participant).or_default();
                        let rotation = match orientation {
                            DeviceOrientation::Portrait => 90,
                            DeviceOrientation::LandscapeLeft => 180,
                            DeviceOrientation::PortraitUpsideDown => 270,
                            DeviceOrientation::LandscapeRight => 0,
                        };
                        entry.update_rotation(rotation);
                    },
                    RenderControl::Finish => break
                }
            }
            info!("FT CLEANUP: Dispatch");
        });

        state.session.frame_handler.configure_handler(Box::new(move |msg| {
            match msg.r#type {
                ChannelType::H264 | ChannelType::H265 => {
                    // Video unsupported on this device: the decoders can't be
                    // created, so bin any incoming video frames instead of
                    // forwarding them to the render pipeline.
                    return;
                    // let _ = render_send.try_send(RenderControl::Participant(RenderCommandTarget {
                    //     participant: msg.participant,
                    //     r#type: msg.r#type,
                    //     id: 0,
                    //     command: RenderCommand::Frame(msg),
                    // }));
                },
                ChannelType::Aac | ChannelType::Evs => {
                    let ChannelFrame::Sample(s) = &msg.frame else { panic!() };
                    let samples_per_packet = if msg.prev_dropped > 0 && msg.r#type == ChannelType::Evs {
                        AudioParser(s).count() as u16
                    } else { 1 };
                    for (idx, m) in AudioParser(s).enumerate() {
                        let _ = render_send.try_send(RenderControl::Participant(RenderCommandTarget {
                            participant: msg.participant,
                            r#type: msg.r#type,
                            id: 0,
                            command: RenderCommand::Frame(ChannelMessage {
                                frame: ChannelFrame::Sample(m.to_vec()),
                                timestamp: msg.timestamp.wrapping_add(idx as u32 * 480 /* frames per packet, 24000 hz with a 24000hz RTP clock */),
                                prev_dropped: if idx == 0 { msg.prev_dropped * samples_per_packet } else { 0 },
                                ..msg.clone()
                            }),
                        }));
                    }
                },
                _ => return
            }
        }));
        let mut control_recv = state.session.control.lock().await.take().unwrap();
        let state_3 = Arc::downgrade(&state);
        tokio::spawn(async move {
            while let Some(command) = control_recv.recv().await {
                let Some(state_3) = state_3.upgrade() else { break };
                state_3.process_control(command).await;
            }
         });

        let rtp_start = SystemTime::now();

        // Camera/video sending is unsupported on this device — do not start the
        // camera capture+encode pipeline. (Audio-only call.)
        // state.publish_camera(recv_control, rtp_start);
        let _ = &recv_control; // keep the channel end alive; unused without the camera pipeline
        state.publish_audio(mic_trigger_recv, mic_recv, rtp_start);

        info!("FaceTime Done");

        (state, is_video)
    }

    fn publish_audio(&self, trigger: std::sync::mpsc::Receiver<()>, control: std::sync::mpsc::Receiver<MicData>, rtp_base_time: SystemTime) {
        // let sender = self.session.create_audio_sender(Some(0), &[1]).await;
        let session = Arc::downgrade(&self.session);
        let self_sender = self.mic_control.clone();

        std::thread::spawn(move || {
            let mut capture = AudioCapture::new(rtp_base_time, self_sender);

            let mut has_started = false;
            'exit: loop {
                match trigger.recv_timeout(capture.timeout()) {
                    Ok(()) | Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {},
                    Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
                };

                while let Ok(recv) = control.try_recv() {
                    match recv {
                        MicData::EnableStreams(streams) => {
                            info!("SEding ot mic secondcary streams {streams:?}");

                            let Some(session) = session.upgrade() else {
                                break 'exit;
                            };

                            let contains_u1 = streams.iter().any(|i| i.is_none());
                            let contains_group = streams.iter().any(|i| i.is_some());
                            if contains_group {
                                capture.sender.entry(Some(0)).or_insert_with(|| rt().block_on(session.create_audio_sender(Some(0), &[1])));
                            } else {
                                capture.sender.remove(&Some(0));
                            }

                            if contains_u1 {
                                capture.sender.entry(None).or_insert_with(|| rt().block_on(session.create_audio_sender(None, &[])));
                            } else {
                                capture.sender.remove(&None);
                            }

                            if !has_started {
                                capture.start();
                                has_started = true;
                            }
                        },
                        MicData::Enabled(e) => capture.set_muted(!e),
                        MicData::Finish => break 'exit,
                    }
                }
                
                capture.process_consume();
            }
            info!("FT CLEANUP: Closing Audio");
        });
    }

    fn publish_camera(&self, control: std::sync::mpsc::Receiver<CameraData>, rtp_base_time: SystemTime) {
        let surface_ref = self.display_activity.clone();
        let self_sender = self.camera_data.clone();
        let session = Arc::downgrade(&self.session);

        // let group = session.create_video_sender(Some(0), &[1, 2, 3, 4, 5]).await;
        
        std::thread::spawn(move || {
            let mut capture = CameraCapture::new(self_sender, surface_ref, rtp_base_time);
            
            let mut has_started = false;
            loop {
                let control = match control.recv() {
                    Ok(target) => target,
                    Err(std::sync::mpsc::RecvError) => break,
                };

                match control {
                    CameraData::Buffer(recv, info) => {
                        capture.handle_buffer(recv, info);
                    },
                    CameraData::GenerateKeyFrame => {
                        capture.generate_key_frame();
                    },
                    CameraData::EnableStreams(streams) => {
                        let Some(session) = session.upgrade() else {
                            break;
                        };
                        info!("SEding ot secondcary streams {streams:?}");

                        let contains_u1 = streams.iter().any(|i| i.is_none());
                        let contains_group = streams.iter().any(|i| i.is_some());
                        if contains_group {
                            capture.sender.entry(Some(0)).or_insert_with(|| rt().block_on(session.create_video_sender(Some(0), &[1, 2, 3, 4, 5])));
                            capture.is_u1 = false;
                            let lowest_group = streams.iter().filter_map(|i| *i).min().unwrap();
                            // just set sending bitrate to the lowest consumer
                            let target_bitrate = session.av_config.video_streams[&lowest_group].max_network_bitrate_v2();
                            capture.set_bitrate(target_bitrate as usize);
                        } else {
                            capture.sender.remove(&Some(0));
                            if !capture.is_u1 {
                                // entering U1 mode
                                capture.generate_key_frame();
                            }
                            capture.is_u1 = true;
                        }

                        if contains_u1 {
                            capture.sender.entry(None).or_insert_with(|| rt().block_on(session.create_video_sender(None, &[])));
                        } else {
                            capture.sender.remove(&None);
                        }

                        if !has_started {
                            capture.encoder.start().unwrap();
                            has_started = true;
                        }
                    },
                    CameraData::SetVideoBitrate(bitrate) => {
                        capture.set_bitrate(bitrate);
                    },
                    CameraData::Finish => break,
                }
            }
            info!("FT CLEANUP: Closing Camera");
        });
    }

    fn get_surface_for(&self, participant: u64) -> Option<NativeWindow> {
        let vm = JVM.get().expect("JVM not initialized");

        let mut attach = vm.attach_current_thread().unwrap();
        let result = attach.call_method(&self.display_activity, "buildSurface", "(J)Landroid/view/Surface;", &[JValue::Long(participant as i64)]).unwrap().l().unwrap();
        if result.is_null() {
            return None
        }
        Some(unsafe { NativeWindow::from_surface(attach.get_raw(), *result).unwrap() })
    }

    fn update_active_participants(&self, active: HashSet<u64>) {
        let vm = JVM.get().expect("JVM not initialized");

        let mut attach = vm.attach_current_thread().unwrap();
        let list = active.into_iter().map(|i| i as jlong).collect::<Vec<_>>();

        let array = attach.new_long_array(list.len() as i32).unwrap();
        attach.set_long_array_region(&array, 0, &list).unwrap();

        attach.call_method(&self.display_activity, "updateParticipants", "([J)V", &[JValue::Object(&array)]).unwrap();
    }

    async fn process_control(&self, command: AVControlCommand) {
        match command {
            AVControlCommand::AVControl { participant, data: VCControlData::GenerateKeyFrame(_) } => {
                let _ = self.camera_data.try_send(CameraData::GenerateKeyFrame);
            },
            AVControlCommand::AVControl { participant, data: VCControlData::DeviceOrientation(orientation) } => {
                let _ = self.render_send.try_send(RenderControl::UpdateOrientation(participant as u64, orientation));

                let vm = JVM.get().expect("JVM not initialized");

                let mut attach = vm.attach_current_thread().unwrap();

                attach.call_method(&self.display_activity, "setParticipantOrientation", "(JI)V", &[JValue::Long(participant), JValue::Int(orientation as i32)]).unwrap();
            }
            AVControlCommand::SelectStreams { video_streams, audio_streams } => {
                let _ = self.camera_data.try_send(CameraData::EnableStreams(video_streams));
                let _ = self.mic_data.try_send(MicData::EnableStreams(audio_streams));
                let _ = self.mic_control.try_send(());
            },
            AVControlCommand::SelectVideoBitrate(bitrate) => {
                let _ = self.camera_data.try_send(CameraData::SetVideoBitrate(bitrate));
            },
            AVControlCommand::ActiveParticipants(active) => {
                info!("Updated participants {active:?}");
                let _ = self.render_send.try_send(RenderControl::ActiveParticipants(active.clone()));
                self.update_active_participants(active);
            },
            _ => {}
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_createAvc(mut env: JNIEnv, object: JObject, uuid: JString) -> jlong {
    // Unlike BlueBubbles, the FaceTime client isn't passed in as a pointer — it's
    // the process-global one stood up at Smart Txt init (crate::st().facetime).
    let Some(item) = crate::st().facetime.clone() else {
        error!("createAvc: no FaceTime client up");
        return 0;
    };

    let uuid: String = env.get_string(&uuid).unwrap().into();
    let native_class = env.new_global_ref(&object).unwrap();

    let (res, _is_video) = rt().block_on(FaceTimeNativeState::new(item, &uuid, native_class));

    Arc::into_raw(res) as jlong
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_destroyAvc(mut env: JNIEnv, object: JObject, avc: jlong) {
    let ptr_ref = unsafe { Arc::from_raw(avc as *const FaceTimeNativeState) };
    let uuid = ptr_ref.uuid.clone();
    let ft = ptr_ref.ft.clone();
    rt().spawn(async move {
        info!("Leaving call! {uuid}");
        let mut lock = ft.state.write().await;
        let state = lock.sessions.get_mut(&uuid).expect("state");
        if let Err(e) = ft.leave(state).await {
            warn!("Failed to leave call {e}");
        } else {
            info!("Left call!");
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_enableCamera(mut env: JNIEnv, object: JObject, avc: jlong, enabled: jboolean) {
    let ptr_ref = unsafe { &*(avc as *const FaceTimeNativeState) };
    let enabled = enabled == 1;
    let session = ptr_ref.session.clone();
    let guid = ptr_ref.uuid.clone();
    let ft_client = ptr_ref.ft.clone();
    info!("Setting video state to {enabled}");
    rt().spawn(async move {
        if let Err(e) = session.set_video_enabled(enabled).await {
            warn!("Failed to set video to {enabled}");
        }
        if enabled {
            if let Err(e) = ft_client.upgrade_to_video(&guid, true).await {
                warn!("Failed to send upgrade request {e}")
            }
        }
    });
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_enableMicrophone(mut env: JNIEnv, object: JObject, avc: jlong, enabled: jboolean) {
    let ptr_ref = unsafe { &*(avc as *const FaceTimeNativeState) };
    let _ = ptr_ref.mic_data.try_send(MicData::Enabled(enabled == 1));
    let _ = ptr_ref.mic_control.try_send(());
}

#[no_mangle]
pub extern "system" fn Java_com_offlineinc_dumbdownlauncher_facetime_FaceTimeInCallService_refreshSurfaces(mut env: JNIEnv, object: JObject, avc: jlong,) {
    let ptr_ref = unsafe { &*(avc as *const FaceTimeNativeState) };
    let _ = ptr_ref.render_send.try_send(RenderControl::UpdateSurfaces);
}

/// Surface a FaceTime control message to Kotlin (ring / join / leave / decline /
/// etc.) so the launcher can drive its ringing screens and decide when to start
/// the in-call service. Called from the APS receive loop for every [`FTMessage`]
/// the [`FTClient`] emits. Reduces each variant to (kind, guid, ring) and upcalls
/// the static `FaceTimeEvents.onEvent`.
pub fn dispatch_ft_message(msg: &FTMessage) {
    let (kind, guid, ring): (&str, String, bool) = match msg {
        FTMessage::JoinEvent { guid, ring, .. } => ("join", guid.clone(), *ring),
        FTMessage::AddMembers { guid, ring, .. } => ("add_members", guid.clone(), *ring),
        FTMessage::RemoveMembers { guid, .. } => ("remove_members", guid.clone(), false),
        FTMessage::LeaveEvent { guid, .. } => ("leave", guid.clone(), false),
        FTMessage::Ring { guid } => ("ring", guid.clone(), true),
        FTMessage::Decline { guid } => ("decline", guid.clone(), false),
        FTMessage::RespondedElsewhere { guid } => ("responded_elsewhere", guid.clone(), false),
        FTMessage::Connected { guid } => ("connected", guid.clone(), false),
        FTMessage::Disconnected { guid } => ("disconnected", guid.clone(), false),
        FTMessage::LinkChanged { guid } => ("link_changed", guid.clone(), false),
        FTMessage::LetMeInRequest(_) => ("let_me_in", String::new(), false),
    };

    let Some(global_cls) = FT_EVENTS_CLASS.get() else {
        warn!("dispatch_ft_message: FaceTimeEvents class not cached (JNI_OnLoad)");
        return;
    };
    let Some(vm) = JVM.get() else {
        warn!("dispatch_ft_message: JVM not initialized");
        return;
    };
    let Ok(mut env) = vm.attach_current_thread() else {
        warn!("dispatch_ft_message: could not attach JVM thread");
        return;
    };
    let (Ok(k), Ok(g)) = (env.new_string(kind), env.new_string(&guid)) else {
        return;
    };
    // Reuse the app-class-loader-resolved class; a bare name here would hit the
    // system class loader on this native thread and fail (ClassNotFoundException).
    let class = unsafe { JClass::from_raw(global_cls.as_obj().as_raw()) };
    let res = env.call_static_method(
        &class,
        "onEvent",
        "(Ljava/lang/String;Ljava/lang/String;Z)V",
        &[JValue::Object(&k), JValue::Object(&g), JValue::Bool(ring as u8)],
    );
    if let Err(e) = res {
        warn!("dispatch_ft_message: onEvent upcall failed: {e:?}");
    }
}
