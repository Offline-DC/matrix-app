//! Import hooks (feature `emulate`) — the handlers the emulated nac code calls
//! out to, faithfully ported from the runtime-verified `../nacserver/nac.py`.
//!
//! The behaviours here are dictated by the binary's ABI (how it reads IOKit /
//! CoreFoundation), not copied from any GPL/SSPL source. The security-critical
//! hook is `_IORegistryEntryCreateCFProperty`, which serves the spoofed
//! hardware identity from [`HookState::iokit`].

use std::collections::HashMap;

use unicorn_engine::{RegisterX86, Unicorn};

use crate::error::{AbsintheError, Result};
use crate::hardware::{HardwareConfig, IoKitValue};

const ARG_REGS: [RegisterX86; 6] = [
    RegisterX86::RDI,
    RegisterX86::RSI,
    RegisterX86::RDX,
    RegisterX86::RCX,
    RegisterX86::R8,
    RegisterX86::R9,
];

// When a data-symbol constant is read through the 0xC3-filled hook page, its 8
// bytes come back as this sentinel (see nacserver/nac.py).
const C3_SENTINEL: u64 = 0xC3C3_C3C3_C3C3_C3C3;

/// A CoreFoundation object as the emulator models it. Handles are 1-based
/// indices into [`HookState::cf`].
#[derive(Clone, Debug)]
enum Cf {
    Data(Vec<u8>),
    Str(String),
    Dict(HashMap<String, DictVal>),
}

#[derive(Clone, Debug)]
enum DictVal {
    Str(String),
    Handle(usize),
    Raw(u64),
}

pub struct HookState {
    pub heap: u64,
    pub validation_ctx: u64,
    pub fault: Option<AbsintheError>,
    cf: Vec<Cf>,
    eth_iter: bool,
    iokit: Vec<(String, OwnedIoVal)>,
    root_disk_uuid: String,
}

#[derive(Clone)]
enum OwnedIoVal {
    Str(String),
    Data(Vec<u8>),
}

impl HookState {
    pub fn new(hw: &HardwareConfig) -> HookState {
        let iokit = hw
            .iokit_properties()
            .into_iter()
            .map(|(k, v)| {
                let ov = match v {
                    IoKitValue::Str(s) => OwnedIoVal::Str(s.to_string()),
                    // board-id / product-name are CFData carrying a
                    // NUL-terminated C string.
                    IoKitValue::CStr(s) => {
                        let mut b = s.as_bytes().to_vec();
                        b.push(0);
                        OwnedIoVal::Data(b)
                    }
                    IoKitValue::Data(d) => OwnedIoVal::Data(d.to_vec()),
                };
                (k.to_string(), ov)
            })
            .collect();
        HookState {
            heap: crate::jelly::HEAP_BASE,
            validation_ctx: 0,
            fault: None,
            cf: Vec::new(),
            eth_iter: false,
            iokit,
            root_disk_uuid: hw.root_disk_uuid.clone(),
        }
    }

    fn cf_new(&mut self, obj: Cf) -> u64 {
        self.cf.push(obj);
        self.cf.len() as u64 // 1-based; 0 == NULL
    }
    fn cf_get(&self, handle: u64) -> Result<&Cf> {
        self.cf
            .get(handle as usize - 1)
            .ok_or_else(|| AbsintheError::Emulation(format!("bad CF handle {handle}")))
    }
}

type Emu<'a> = Unicorn<'a, ()>;

fn a(uc: &Emu, n: usize) -> u64 {
    uc.reg_read(ARG_REGS[n]).unwrap_or(0)
}
fn set_ret(uc: &mut Emu, v: u64) {
    uc.reg_write(RegisterX86::RAX, v).ok();
}
fn rd(uc: &Emu, addr: u64, len: usize) -> Result<Vec<u8>> {
    uc.mem_read_as_vec(addr, len)
        .map_err(|e| AbsintheError::Emulation(format!("hook read {addr:#x}: {e:?}")))
}
fn wr(uc: &mut Emu, addr: u64, data: &[u8]) -> Result<()> {
    uc.mem_write(addr, data)
        .map_err(|e| AbsintheError::Emulation(format!("hook write {addr:#x}: {e:?}")))
}

/// Read a CFStringRef-style struct pointer → Rust String (isa, flags, ptr, len).
fn cfstr_ptr(uc: &Emu, ptr: u64) -> Result<String> {
    let hdr = rd(uc, ptr, 32)?;
    let str_ptr = u64::from_le_bytes(hdr[16..24].try_into().unwrap());
    let length = u64::from_le_bytes(hdr[24..32].try_into().unwrap());
    let bytes = rd(uc, str_ptr, length as usize)?;
    String::from_utf8(bytes).map_err(|_| AbsintheError::Emulation("cfstr not utf-8".into()))
}
fn cstr_ptr(uc: &Emu, ptr: u64) -> Result<String> {
    let bytes = rd(uc, ptr, 256)?;
    let end = bytes.iter().position(|&c| c == 0).unwrap_or(bytes.len());
    String::from_utf8(bytes[..end].to_vec())
        .map_err(|_| AbsintheError::Emulation("cstr not utf-8".into()))
}

/// Resolve a dict key argument to a String (raw string, or a CFString handle).
fn maybe_key(st: &HookState, val: u64) -> Result<String> {
    if val != 0 && val as usize <= st.cf.len() {
        if let Cf::Str(s) = st.cf_get(val)? {
            return Ok(s.clone());
        }
    }
    Err(AbsintheError::Emulation(format!(
        "dict key {val:#x} is not a string"
    )))
}

fn malloc(st: &mut HookState, size: u64) -> u64 {
    let addr = st.heap;
    st.heap = (st.heap + size + 7) & !7;
    addr
}

/// Dispatch one imported call. Reads args from registers, mutates `st` and
/// emulator memory, and writes the return value into RAX.
pub fn dispatch(uc: &mut Emu, st: &mut HookState, sym: &str) -> Result<()> {
    match sym {
        // ---- libc / memory --------------------------------------------------
        "_malloc" => {
            let n = a(uc, 0);
            let p = malloc(st, n);
            set_ret(uc, p);
        }
        "_free" | "___stack_chk_guard" | "_sysctlbyname" | "_CFRelease" | "_IOObjectRelease"
        | "_kIOMasterPortDefault" | "_kCFAllocatorDefault" | "_kCFBooleanTrue"
        | "_kDADiskDescriptionVolumeUUIDKey" | "_statfs$INODE64" => {
            set_ret(uc, 0);
        }
        "_memcpy" => {
            let (dst, src, n) = (a(uc, 0), a(uc, 1), a(uc, 2));
            let data = rd(uc, src, n as usize)?;
            wr(uc, dst, &data)?;
            set_ret(uc, dst);
        }
        "___memset_chk" => {
            let (dst, c, n) = (a(uc, 0), a(uc, 1), a(uc, 2));
            wr(uc, dst, &vec![(c & 0xFF) as u8; n as usize])?;
            set_ret(uc, dst);
        }
        "___bzero" => {
            let (ptr, n) = (a(uc, 0), a(uc, 1));
            wr(uc, ptr, &vec![0u8; n as usize])?;
            set_ret(uc, 0);
        }
        "_arc4random" => {
            set_ret(uc, rand_u32() as u64);
        }
        // ---- CoreFoundation -------------------------------------------------
        "_CFGetTypeID" => {
            let id = match st.cf_get(a(uc, 0))? {
                Cf::Data(_) => 1,
                _ => 2,
            };
            set_ret(uc, id);
        }
        "_CFStringGetTypeID" => set_ret(uc, 2),
        "_CFDataGetTypeID" => set_ret(uc, 1),
        "_CFDataGetLength" => {
            let len = match st.cf_get(a(uc, 0))? {
                Cf::Data(d) => d.len() as u64,
                _ => return Err(AbsintheError::Emulation("CFDataGetLength on non-data".into())),
            };
            set_ret(uc, len);
        }
        "_CFDataGetBytes" => {
            let (h, start, end, buf) = (a(uc, 0), a(uc, 1), a(uc, 2), a(uc, 3));
            let chunk = match st.cf_get(h)? {
                Cf::Data(d) => d[start as usize..end as usize].to_vec(),
                _ => return Err(AbsintheError::Emulation("CFDataGetBytes on non-data".into())),
            };
            wr(uc, buf, &chunk)?;
            set_ret(uc, chunk.len() as u64);
        }
        "_CFStringGetLength" => {
            let len = match st.cf_get(a(uc, 0))? {
                Cf::Str(s) => s.chars().count() as u64,
                _ => return Err(AbsintheError::Emulation("CFStringGetLength on non-str".into())),
            };
            set_ret(uc, len);
        }
        "_CFStringGetCString" => {
            let (h, buf) = (a(uc, 0), a(uc, 1));
            let s = match st.cf_get(h)? {
                Cf::Str(s) => s.clone(),
                _ => return Err(AbsintheError::Emulation("CFStringGetCString on non-str".into())),
            };
            wr(uc, buf, s.as_bytes())?;
            set_ret(uc, 1);
        }
        "_CFStringGetMaximumSizeForEncoding" => set_ret(uc, a(uc, 0)),
        "_CFUUIDCreateString" => set_ret(uc, a(uc, 1)),
        "_CFDictionaryCreateMutable" => {
            let h = st.cf_new(Cf::Dict(HashMap::new()));
            set_ret(uc, h);
        }
        "_CFDictionaryGetValue" => {
            let (dh, key_arg) = (a(uc, 0), a(uc, 1));
            let key = if key_arg == C3_SENTINEL {
                "DADiskDescriptionVolumeUUIDKey".to_string()
            } else {
                maybe_key(st, key_arg)?
            };
            let val = match st.cf_get(dh)? {
                Cf::Dict(d) => d
                    .get(&key)
                    .cloned()
                    .ok_or_else(|| AbsintheError::Emulation(format!("dict key {key} not found")))?,
                _ => return Err(AbsintheError::Emulation("CFDictionaryGetValue on non-dict".into())),
            };
            let obj = match val {
                DictVal::Str(s) => Cf::Str(s),
                DictVal::Handle(h) => st.cf_get(h as u64)?.clone(),
                DictVal::Raw(_) => {
                    return Err(AbsintheError::Emulation("dict value is a raw pointer".into()))
                }
            };
            let h = st.cf_new(obj);
            set_ret(uc, h);
        }
        "_CFDictionarySetValue" => {
            let (dh, key_arg, val_arg) = (a(uc, 0), a(uc, 1), a(uc, 2));
            let key = maybe_key(st, key_arg)?;
            // Resolve value like Python's _maybe.
            let val = if val_arg != 0 && (val_arg as usize) <= st.cf.len() {
                match st.cf_get(val_arg)? {
                    Cf::Str(s) => DictVal::Str(s.clone()),
                    _ => DictVal::Handle(val_arg as usize),
                }
            } else {
                DictVal::Raw(val_arg)
            };
            if let Cf::Dict(d) = st
                .cf
                .get_mut(dh as usize - 1)
                .ok_or_else(|| AbsintheError::Emulation("set on bad dict".into()))?
            {
                d.insert(key, val);
            }
            set_ret(uc, 0);
        }
        // ---- IOKit ----------------------------------------------------------
        "_IORegistryEntryFromPath" => set_ret(uc, 1),
        "_IORegistryEntryCreateCFProperty" => {
            let key = cfstr_ptr(uc, a(uc, 1))?;
            let found = st.iokit.iter().find(|(k, _)| *k == key).map(|(_, v)| v.clone());
            match found {
                Some(OwnedIoVal::Str(s)) => {
                    let h = st.cf_new(Cf::Str(s));
                    set_ret(uc, h);
                }
                Some(OwnedIoVal::Data(d)) => {
                    let h = st.cf_new(Cf::Data(d));
                    set_ret(uc, h);
                }
                None => set_ret(uc, 0),
            }
        }
        "_IOServiceMatching" => {
            let name = cstr_ptr(uc, a(uc, 0))?;
            let name_h = st.cf_new(Cf::Str(name));
            let mut dict = HashMap::new();
            dict.insert("IOProviderClass".to_string(), DictVal::Handle(name_h as usize));
            let h = st.cf_new(Cf::Dict(dict));
            set_ret(uc, h);
        }
        "_IOServiceGetMatchingService" => set_ret(uc, 92),
        "_IOServiceGetMatchingServices" => {
            st.eth_iter = true;
            wr(uc, a(uc, 2), &[93])?;
            set_ret(uc, 0);
        }
        "_IOIteratorNext" => {
            if st.eth_iter {
                st.eth_iter = false;
                set_ret(uc, 94);
            } else {
                set_ret(uc, 0);
            }
        }
        "_IORegistryEntryGetParentEntry" => {
            let entry = a(uc, 0);
            wr(uc, a(uc, 2), &[((entry + 100) & 0xFF) as u8])?;
            set_ret(uc, 0);
        }
        // ---- DiskArbitration ------------------------------------------------
        "_DASessionCreate" => set_ret(uc, 201),
        "_DADiskCreateFromBSDName" => set_ret(uc, 202),
        "_DADiskCopyDescription" => {
            let uuid = st.root_disk_uuid.clone();
            let mut dict = HashMap::new();
            dict.insert("DADiskDescriptionVolumeUUIDKey".to_string(), DictVal::Str(uuid));
            let h = st.cf_new(Cf::Dict(dict));
            set_ret(uc, h);
        }
        other => {
            return Err(AbsintheError::UnhookedImport(other.to_string()));
        }
    }
    Ok(())
}

/// Small self-contained RNG (avoids a `rand` dependency). Seeded from time; the
/// nac request only needs unpredictable nonces, not cryptographic strength on
/// our side (Apple re-randomises server-side).
fn rand_u32() -> u32 {
    use std::cell::Cell;
    use std::time::{SystemTime, UNIX_EPOCH};
    thread_local! {
        static STATE: Cell<u64> = Cell::new(0);
    }
    STATE.with(|s| {
        let mut x = s.get();
        if x == 0 {
            x = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_nanos() as u64)
                .unwrap_or(0x9E37_79B9_7F4A_7C15)
                | 1;
        }
        // xorshift64*
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        s.set(x);
        ((x.wrapping_mul(0x2545_F491_4F6C_DD1D)) >> 32) as u32
    })
}
