//! Minimal Mach-O / fat-binary loader for `IMDAppleServices`.
//!
//! We only need enough to: (1) pull the x86_64 slice out of the fat binary,
//! (2) enumerate `LC_SEGMENT_64` for mapping into the emulator, and (3) run the
//! classic `LC_DYLD_INFO(_ONLY)` bind + lazy-bind opcode streams to learn the
//! address of every imported-function pointer slot, so the emulator can
//! redirect each one to a Python-style hook (see `hooks.rs`).
//!
//! All of the parsing here was validated against the real pinned binary (sha1
//! `e1181ccad82e6629d52c6a006645ad87ee59bd13`): the x86_64 slice sits at file
//! offset `0x32b000`, the three nac functions begin at slice offsets `0xB1DB0`
//! / `0xB1DD0` / `0xB1DF0` with the `55 48 89 e5` prologue, and the bind
//! streams resolve 1415 fixups (257 unique symbols) into the `__DATA` segment.

use crate::error::{AbsintheError, Result};

/// SHA-1 of the exact `IMDAppleServices` build the fixed nac offsets belong to.
/// Any other build will have different offsets and must not be used.
pub const EXPECTED_SHA1: &str = "e1181ccad82e6629d52c6a006645ad87ee59bd13";

/// nac function offsets **within the x86_64 slice** (not the fat file).
pub const NAC_INIT_OFF: u64 = 0xB1DB0;
pub const NAC_KEY_ESTABLISHMENT_OFF: u64 = 0xB1DD0;
pub const NAC_SIGN_OFF: u64 = 0xB1DF0;

const CPU_TYPE_X86_64: u32 = 0x0100_0007;
const MH_MAGIC_64: u32 = 0xFEED_FACF;
const LC_SEGMENT_64: u32 = 0x19;
const LC_DYLD_INFO: u32 = 0x22;
const LC_DYLD_INFO_ONLY: u32 = 0x8000_0022;
const POINTER_SIZE: u64 = 8;

// dyld bind opcodes (immediate in low nibble, opcode in high nibble).
const BIND_OPCODE_DONE: u8 = 0x00;
const BIND_OPCODE_SET_DYLIB_ORDINAL_IMM: u8 = 0x10;
const BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB: u8 = 0x20;
const BIND_OPCODE_SET_DYLIB_SPECIAL_IMM: u8 = 0x30;
const BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM: u8 = 0x40;
const BIND_OPCODE_SET_TYPE_IMM: u8 = 0x50;
const BIND_OPCODE_SET_ADDEND_SLEB: u8 = 0x60;
const BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB: u8 = 0x70;
const BIND_OPCODE_ADD_ADDR_ULEB: u8 = 0x80;
const BIND_OPCODE_DO_BIND: u8 = 0x90;
const BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB: u8 = 0xA0;
const BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED: u8 = 0xB0;
const BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB: u8 = 0xC0;

#[derive(Clone, Debug)]
pub struct Segment {
    pub name: String,
    pub vmaddr: u64,
    pub vmsize: u64,
    pub fileoff: u64,
    pub filesize: u64,
}

/// One resolved import: the code reads a pointer at `address`; we point it at a
/// hook trampoline keyed by `symbol`.
#[derive(Clone, Debug)]
pub struct Bind {
    pub address: u64,
    pub symbol: String,
}

pub struct MachOImage {
    /// The x86_64 slice bytes (the thing we map into the emulator at vmaddr 0).
    pub slice: Vec<u8>,
    pub segments: Vec<Segment>,
    pub binds: Vec<Bind>,
}

fn rd_u32(b: &[u8], off: usize) -> Result<u32> {
    b.get(off..off + 4)
        .map(|s| u32::from_le_bytes(s.try_into().unwrap()))
        .ok_or_else(|| AbsintheError::Macho(format!("u32 read out of bounds at {off}")))
}
fn rd_u32_be(b: &[u8], off: usize) -> Result<u32> {
    b.get(off..off + 4)
        .map(|s| u32::from_be_bytes(s.try_into().unwrap()))
        .ok_or_else(|| AbsintheError::Macho(format!("u32(be) read out of bounds at {off}")))
}
fn rd_u64(b: &[u8], off: usize) -> Result<u64> {
    b.get(off..off + 8)
        .map(|s| u64::from_le_bytes(s.try_into().unwrap()))
        .ok_or_else(|| AbsintheError::Macho(format!("u64 read out of bounds at {off}")))
}

fn read_uleb(b: &[u8], i: &mut usize) -> Result<u64> {
    let mut result = 0u64;
    let mut shift = 0u32;
    loop {
        let byte = *b
            .get(*i)
            .ok_or_else(|| AbsintheError::Macho("truncated uleb128".into()))?;
        *i += 1;
        result |= ((byte & 0x7f) as u64) << shift;
        if byte & 0x80 == 0 {
            break;
        }
        shift += 7;
    }
    Ok(result)
}

impl MachOImage {
    /// Extract and parse the x86_64 slice from a fat `IMDAppleServices`.
    pub fn from_fat(data: &[u8]) -> Result<MachOImage> {
        let magic = rd_u32_be(data, 0)?;
        // 0xCAFEBABE = fat (big-endian header). If it's already a thin 64-bit
        // Mach-O, accept it directly.
        let slice: Vec<u8> = if magic == 0xCAFE_BABE {
            let nfat = rd_u32_be(data, 4)? as usize;
            let mut found = None;
            for i in 0..nfat {
                let base = 8 + i * 20;
                let cputype = rd_u32_be(data, base)?;
                let offset = rd_u32_be(data, base + 8)? as usize;
                let size = rd_u32_be(data, base + 12)? as usize;
                if cputype == CPU_TYPE_X86_64 {
                    found = Some(
                        data.get(offset..offset + size)
                            .ok_or_else(|| AbsintheError::Macho("fat slice out of bounds".into()))?
                            .to_vec(),
                    );
                    break;
                }
            }
            found.ok_or_else(|| AbsintheError::Macho("no x86_64 slice in fat binary".into()))?
        } else if rd_u32(data, 0)? == MH_MAGIC_64 {
            data.to_vec()
        } else {
            return Err(AbsintheError::Macho(format!(
                "not a fat or 64-bit Mach-O (magic {magic:#x})"
            )));
        };

        if rd_u32(&slice, 0)? != MH_MAGIC_64 {
            return Err(AbsintheError::Macho("slice is not MH_MAGIC_64".into()));
        }

        let ncmds = rd_u32(&slice, 16)? as usize;
        let mut segments = Vec::new();
        let mut dyld_bind: Option<(usize, usize)> = None;
        let mut dyld_lazy: Option<(usize, usize)> = None;

        let mut p = 32usize; // past mach_header_64
        for _ in 0..ncmds {
            let cmd = rd_u32(&slice, p)?;
            let cmdsize = rd_u32(&slice, p + 4)? as usize;
            if cmdsize == 0 {
                return Err(AbsintheError::Macho("zero-size load command".into()));
            }
            match cmd {
                LC_SEGMENT_64 => {
                    let name_bytes = &slice[p + 8..p + 24];
                    let name = String::from_utf8_lossy(
                        &name_bytes[..name_bytes.iter().position(|&c| c == 0).unwrap_or(16)],
                    )
                    .into_owned();
                    segments.push(Segment {
                        name,
                        vmaddr: rd_u64(&slice, p + 24)?,
                        vmsize: rd_u64(&slice, p + 32)?,
                        fileoff: rd_u64(&slice, p + 40)?,
                        filesize: rd_u64(&slice, p + 48)?,
                    });
                }
                LC_DYLD_INFO | LC_DYLD_INFO_ONLY => {
                    let bind_off = rd_u32(&slice, p + 16)? as usize;
                    let bind_size = rd_u32(&slice, p + 20)? as usize;
                    let lazy_off = rd_u32(&slice, p + 32)? as usize;
                    let lazy_size = rd_u32(&slice, p + 36)? as usize;
                    dyld_bind = Some((bind_off, bind_size));
                    dyld_lazy = Some((lazy_off, lazy_size));
                }
                _ => {}
            }
            p += cmdsize;
        }

        let mut binds = Vec::new();
        for (off, size) in [dyld_bind, dyld_lazy].into_iter().flatten() {
            if size == 0 {
                continue;
            }
            let stream = slice
                .get(off..off + size)
                .ok_or_else(|| AbsintheError::Macho("dyld bind stream out of bounds".into()))?;
            Self::parse_binds(stream, &segments, &mut binds)?;
        }

        Ok(MachOImage {
            slice,
            segments,
            binds,
        })
    }

    /// Run one classic dyld bind/lazy-bind opcode stream, appending fixups.
    fn parse_binds(stream: &[u8], segments: &[Segment], out: &mut Vec<Bind>) -> Result<()> {
        let mut i = 0usize;
        let mut seg_index = 0usize;
        let mut seg_offset = 0u64;
        let mut symbol: Option<String> = None;

        let addr_of = |seg_index: usize, seg_offset: u64| -> Result<u64> {
            let seg = segments.get(seg_index).ok_or_else(|| {
                AbsintheError::Macho(format!("bind references bad segment {seg_index}"))
            })?;
            Ok(seg.vmaddr + seg_offset)
        };
        let push = |out: &mut Vec<Bind>, symbol: &Option<String>, addr: u64| -> Result<()> {
            let sym = symbol
                .clone()
                .ok_or_else(|| AbsintheError::Macho("DO_BIND without a symbol".into()))?;
            out.push(Bind {
                address: addr,
                symbol: sym,
            });
            Ok(())
        };

        while i < stream.len() {
            let byte = stream[i];
            i += 1;
            let opcode = byte & 0xF0;
            let imm = byte & 0x0F;
            match opcode {
                BIND_OPCODE_DONE => {}
                // Library ordinal opcodes — we don't care which dylib.
                BIND_OPCODE_SET_DYLIB_ORDINAL_IMM | BIND_OPCODE_SET_DYLIB_SPECIAL_IMM => {}
                BIND_OPCODE_SET_DYLIB_ORDINAL_ULEB => {
                    read_uleb(stream, &mut i)?;
                }
                BIND_OPCODE_SET_SYMBOL_TRAILING_FLAGS_IMM => {
                    let start = i;
                    while *stream
                        .get(i)
                        .ok_or_else(|| AbsintheError::Macho("unterminated bind symbol".into()))?
                        != 0
                    {
                        i += 1;
                    }
                    symbol = Some(String::from_utf8_lossy(&stream[start..i]).into_owned());
                    i += 1; // skip NUL
                }
                BIND_OPCODE_SET_TYPE_IMM => {}
                BIND_OPCODE_SET_ADDEND_SLEB => {
                    read_uleb(stream, &mut i)?; // addend; unused for our purpose
                }
                BIND_OPCODE_SET_SEGMENT_AND_OFFSET_ULEB => {
                    seg_index = imm as usize;
                    seg_offset = read_uleb(stream, &mut i)?;
                }
                BIND_OPCODE_ADD_ADDR_ULEB => {
                    seg_offset = seg_offset.wrapping_add(read_uleb(stream, &mut i)?);
                }
                BIND_OPCODE_DO_BIND => {
                    push(out, &symbol, addr_of(seg_index, seg_offset)?)?;
                    seg_offset += POINTER_SIZE;
                }
                BIND_OPCODE_DO_BIND_ADD_ADDR_ULEB => {
                    push(out, &symbol, addr_of(seg_index, seg_offset)?)?;
                    seg_offset += POINTER_SIZE + read_uleb(stream, &mut i)?;
                }
                BIND_OPCODE_DO_BIND_ADD_ADDR_IMM_SCALED => {
                    push(out, &symbol, addr_of(seg_index, seg_offset)?)?;
                    seg_offset += POINTER_SIZE + (imm as u64) * POINTER_SIZE;
                }
                BIND_OPCODE_DO_BIND_ULEB_TIMES_SKIPPING_ULEB => {
                    let count = read_uleb(stream, &mut i)?;
                    let skip = read_uleb(stream, &mut i)?;
                    for _ in 0..count {
                        push(out, &symbol, addr_of(seg_index, seg_offset)?)?;
                        seg_offset += POINTER_SIZE + skip;
                    }
                }
                other => {
                    return Err(AbsintheError::Macho(format!(
                        "unknown bind opcode {other:#x}"
                    )))
                }
            }
        }
        Ok(())
    }

    /// Cheap integrity gate that doesn't need a SHA-1 dependency: verify the
    /// three nac functions still start with the `push rbp; mov rbp,rsp`
    /// prologue at their fixed offsets. If the binary drifted, these fail.
    pub fn nac_prologues_ok(&self) -> bool {
        const PROLOGUE: [u8; 4] = [0x55, 0x48, 0x89, 0xe5];
        [NAC_INIT_OFF, NAC_KEY_ESTABLISHMENT_OFF, NAC_SIGN_OFF]
            .iter()
            .all(|&off| {
                let o = off as usize;
                self.slice.get(o..o + 4) == Some(&PROLOGUE)
            })
    }
}
