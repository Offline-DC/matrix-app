/*
 * hv_core.c -- minimal FFmpeg front end for the HIT video converter.
 *
 * The HIT (MT6739, Android 8.1) cannot decode 10-bit HEVC (iPhone HDR video):
 * the MTK hardware decoder is 8-bit only and Android 8.1's software HEVC decoder
 * is 8-bit only too, so such a video plays as sound over a black picture. This
 * opens the file with FFmpeg (mov demuxer + hevc decoder, nothing else built in),
 * decodes it in software and hands out 8-bit I420 frames already scaled to the
 * size the Kotlin side will re-encode with the phone's H.264 hardware encoder.
 *
 * Used by both the JNI library (hv_jni.c -> libhitvideo.so) and the adb
 * benchmark (hv_bench.c).
 */
#include "hv_core.h"

#include <string.h>
#include <stdio.h>
#include <stdlib.h>

#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixdesc.h>
#include <libswscale/swscale.h>

struct HV {
    AVFormatContext *fmt;
    AVCodecContext *dec;
    AVPacket *pkt;
    AVFrame *frm;
    struct SwsContext *sws;
    int vidx;
    int out_w, out_h;
    int sws_src_w, sws_src_h, sws_src_fmt;
    int flushing;      /* demuxer hit EOF, decoder being drained */
    int64_t start_pts; /* first timestamp, in stream time base */
};

static void set_err(char *err, int errlen, const char *what, int code) {
    if (!err || errlen <= 0) return;
    char buf[128] = {0};
    if (code < 0) av_strerror(code, buf, sizeof(buf));
    snprintf(err, (size_t) errlen, "%s%s%s", what, code < 0 ? ": " : "", buf);
}

HV *hv_open(const char *path, int threads, int fast, char *err, int errlen) {
    HV *hv = calloc(1, sizeof(HV));
    if (!hv) { set_err(err, errlen, "out of memory", 0); return NULL; }
    hv->vidx = -1;
    hv->start_pts = AV_NOPTS_VALUE;

    int r = avformat_open_input(&hv->fmt, path, NULL, NULL);
    if (r < 0) { set_err(err, errlen, "open", r); hv_close(hv); return NULL; }
    r = avformat_find_stream_info(hv->fmt, NULL);
    if (r < 0) { set_err(err, errlen, "stream info", r); hv_close(hv); return NULL; }

    const AVCodec *codec = NULL;
    r = av_find_best_stream(hv->fmt, AVMEDIA_TYPE_VIDEO, -1, -1, &codec, 0);
    if (r < 0 || !codec) { set_err(err, errlen, "no decodable video stream", r); hv_close(hv); return NULL; }
    hv->vidx = r;
    AVStream *st = hv->fmt->streams[hv->vidx];

    /* Only the video stream matters; skip everything else at the demuxer. */
    for (unsigned i = 0; i < hv->fmt->nb_streams; i++)
        if ((int) i != hv->vidx) hv->fmt->streams[i]->discard = AVDISCARD_ALL;

    hv->dec = avcodec_alloc_context3(codec);
    if (!hv->dec) { set_err(err, errlen, "decoder alloc", 0); hv_close(hv); return NULL; }
    r = avcodec_parameters_to_context(hv->dec, st->codecpar);
    if (r < 0) { set_err(err, errlen, "decoder params", r); hv_close(hv); return NULL; }
    hv->dec->thread_count = threads > 0 ? threads : 4;
    hv->dec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
    hv->dec->pkt_timebase = st->time_base;
    if (fast) {
        /* No deblocking / SAO. Invisible on a 2.4" screen at the size we
         * re-encode to, and a large share of HEVC decode time. */
        hv->dec->skip_loop_filter = AVDISCARD_ALL;
    }
    r = avcodec_open2(hv->dec, codec, NULL);
    if (r < 0) { set_err(err, errlen, "decoder open", r); hv_close(hv); return NULL; }

    hv->pkt = av_packet_alloc();
    hv->frm = av_frame_alloc();
    if (!hv->pkt || !hv->frm) { set_err(err, errlen, "out of memory", 0); hv_close(hv); return NULL; }
    return hv;
}

static int rotation_of(AVStream *st) {
    const AVPacketSideData *sd = av_packet_side_data_get(
        st->codecpar->coded_side_data, st->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (!sd || sd->size < 9 * 4) return 0;
    double rot = -av_display_rotation_get((const int32_t *) sd->data); /* counter-clockwise -> clockwise */
    if (rot != rot) return 0; /* NaN */
    int deg = (int) (rot < 0 ? rot - 0.5 : rot + 0.5);
    deg = ((deg % 360) + 360) % 360;
    return ((deg + 45) / 90 * 90) % 360;
}

void hv_info(HV *hv, int out[HV_INFO_LEN]) {
    memset(out, 0, sizeof(int) * HV_INFO_LEN);
    if (!hv || hv->vidx < 0) return;
    AVStream *st = hv->fmt->streams[hv->vidx];
    AVCodecParameters *cp = st->codecpar;
    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat) cp->format);
    AVRational fr = st->avg_frame_rate.num > 0 ? st->avg_frame_rate : st->r_frame_rate;
    int64_t dur_ms = 0;
    if (st->duration > 0) dur_ms = av_rescale_q(st->duration, st->time_base, (AVRational){1, 1000});
    else if (hv->fmt->duration > 0) dur_ms = hv->fmt->duration / 1000;
    out[HV_INFO_WIDTH] = cp->width;
    out[HV_INFO_HEIGHT] = cp->height;
    out[HV_INFO_BIT_DEPTH] = d ? d->comp[0].depth : 0;
    out[HV_INFO_FPS_NUM] = fr.num;
    out[HV_INFO_FPS_DEN] = fr.den;
    out[HV_INFO_DURATION_MS] = (int) dur_ms;
    out[HV_INFO_ROTATION] = rotation_of(st);
    out[HV_INFO_FRAMES] = (int) st->nb_frames;
    out[HV_INFO_CODEC_ID] = cp->codec_id;
    out[HV_INFO_COLOR_TRC] = cp->color_trc;
}

int hv_set_output(HV *hv, int w, int h) {
    if (!hv || w <= 0 || h <= 0 || (w & 1) || (h & 1)) return -1;
    hv->out_w = w;
    hv->out_h = h;
    if (hv->sws) { sws_freeContext(hv->sws); hv->sws = NULL; }
    return 0;
}

int hv_frame_size(HV *hv) {
    return hv ? hv->out_w * hv->out_h * 3 / 2 : 0;
}

/* Pull the next decoded frame out of the decoder, feeding packets as needed.
 * 1 = got a frame, 0 = end of stream, < 0 = error. */
static int decode_one(HV *hv) {
    for (;;) {
        int r = avcodec_receive_frame(hv->dec, hv->frm);
        if (r == 0) return 1;
        if (r == AVERROR_EOF) return 0;
        if (r != AVERROR(EAGAIN)) return r;
        if (hv->flushing) return 0;

        r = av_read_frame(hv->fmt, hv->pkt);
        if (r == AVERROR_EOF) {
            hv->flushing = 1;
            avcodec_send_packet(hv->dec, NULL);
            continue;
        }
        if (r < 0) return r;
        if (hv->pkt->stream_index == hv->vidx) {
            r = avcodec_send_packet(hv->dec, hv->pkt);
            av_packet_unref(hv->pkt);
            /* A corrupt packet is skipped, not fatal: keep going. */
            if (r < 0 && r != AVERROR(EAGAIN) && r != AVERROR_INVALIDDATA) return r;
        } else {
            av_packet_unref(hv->pkt);
        }
    }
}

/* Downscale one plane by averaging a 2x2 source block per output sample and
 * reduce it to 8 bits. Exact and branch-light; covers what iPhones send
 * (yuv420p10le) and plain 8-bit yuv420p. swscale is only the fallback: its
 * non-"accurate rounding" path corrupts 10-bit input when downscaling (seen on
 * FFmpeg 6.1). */
static void scale_plane(const uint8_t *src, int sls, int sw, int sh, int bytes, int shift,
                        uint8_t *dst, int dw, int dh, int *xs) {
    for (int x = 0; x < dw; x++) {
        int sx = (int) ((int64_t) x * sw / dw);
        xs[x] = sx < sw - 1 ? sx : sw - 2 < 0 ? 0 : sw - 2;
    }
    int x1 = sw > 1 ? 1 : 0;
    for (int y = 0; y < dh; y++) {
        int sy = (int) ((int64_t) y * sh / dh);
        int sy1 = sy + 1 < sh ? sy + 1 : sy;
        const uint8_t *r0 = src + (size_t) sy * sls;
        const uint8_t *r1 = src + (size_t) sy1 * sls;
        uint8_t *d = dst + (size_t) y * dw;
        int add = (1 << (shift + 2)) >> 1;
        if (bytes == 2) {
            const uint16_t *a = (const uint16_t *) r0, *b = (const uint16_t *) r1;
            for (int x = 0; x < dw; x++) {
                int sx = xs[x];
                unsigned v = a[sx] + a[sx + x1] + b[sx] + b[sx + x1];
                v = (v + add) >> (shift + 2);
                d[x] = (uint8_t) (v > 255 ? 255 : v);
            }
        } else {
            for (int x = 0; x < dw; x++) {
                int sx = xs[x];
                unsigned v = r0[sx] + r0[sx + x1] + r1[sx] + r1[sx + x1];
                d[x] = (uint8_t) ((v + 2) >> 2);
            }
        }
    }
}

int hv_next(HV *hv, uint8_t *dst, int dst_size, int64_t *pts_us) {
    if (!hv || !dst || hv->out_w <= 0) return -1;
    if (dst_size < hv_frame_size(hv)) return -1;

    int r = decode_one(hv);
    if (r <= 0) return r;

    AVFrame *f = hv->frm;
    uint8_t *planes[4] = {0};
    int strides[4] = {0};
    av_image_fill_arrays(planes, strides, dst, AV_PIX_FMT_YUV420P, hv->out_w, hv->out_h, 1);

    const AVPixFmtDescriptor *d = av_pix_fmt_desc_get((enum AVPixelFormat) f->format);
    int simple = d && !(d->flags & (AV_PIX_FMT_FLAG_BE | AV_PIX_FMT_FLAG_RGB | AV_PIX_FMT_FLAG_PAL))
                 && d->nb_components == 3 && d->log2_chroma_w == 1 && d->log2_chroma_h == 1
                 && d->comp[0].plane == 0 && d->comp[1].plane == 1 && d->comp[2].plane == 2
                 && d->comp[0].depth >= 8 && d->comp[0].depth <= 16
                 && d->comp[0].step == (d->comp[0].depth > 8 ? 2 : 1);
    if (simple) {
        int bytes = d->comp[0].depth > 8 ? 2 : 1;
        int shift = d->comp[0].depth - 8;
        int *xs = av_malloc(sizeof(int) * (size_t) hv->out_w);
        if (!xs) { av_frame_unref(f); return -2; }
        scale_plane(f->data[0], f->linesize[0], f->width, f->height, bytes, shift,
                    planes[0], hv->out_w, hv->out_h, xs);
        for (int p = 1; p < 3; p++)
            scale_plane(f->data[p], f->linesize[p], (f->width + 1) / 2, (f->height + 1) / 2, bytes, shift,
                        planes[p], hv->out_w / 2, hv->out_h / 2, xs);
        av_free(xs);
    } else {
        if (!hv->sws || hv->sws_src_w != f->width || hv->sws_src_h != f->height || hv->sws_src_fmt != f->format) {
            if (hv->sws) sws_freeContext(hv->sws);
            hv->sws = sws_getContext(f->width, f->height, (enum AVPixelFormat) f->format,
                                     hv->out_w, hv->out_h, AV_PIX_FMT_YUV420P,
                                     SWS_BILINEAR | SWS_ACCURATE_RND, NULL, NULL, NULL);
            if (!hv->sws) { av_frame_unref(f); return -2; }
            hv->sws_src_w = f->width;
            hv->sws_src_h = f->height;
            hv->sws_src_fmt = f->format;
        }
        sws_scale(hv->sws, (const uint8_t *const *) f->data, f->linesize, 0, f->height, planes, strides);
    }

    int64_t ts = f->best_effort_timestamp;
    if (ts == AV_NOPTS_VALUE) ts = f->pts;
    if (ts == AV_NOPTS_VALUE) ts = 0;
    if (hv->start_pts == AV_NOPTS_VALUE) hv->start_pts = ts;
    AVStream *st = hv->fmt->streams[hv->vidx];
    int64_t us = av_rescale_q(ts - hv->start_pts, st->time_base, (AVRational){1, 1000000});
    if (pts_us) *pts_us = us < 0 ? 0 : us;
    av_frame_unref(f);
    return 1;
}

void hv_close(HV *hv) {
    if (!hv) return;
    if (hv->sws) sws_freeContext(hv->sws);
    if (hv->frm) av_frame_free(&hv->frm);
    if (hv->pkt) av_packet_free(&hv->pkt);
    if (hv->dec) avcodec_free_context(&hv->dec);
    if (hv->fmt) avformat_close_input(&hv->fmt);
    free(hv);
}
