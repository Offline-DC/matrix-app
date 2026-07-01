//! The x86-64 emulation harness (feature `emulate`).
//!
//! Faithful Rust port of the clean-room emulator verified in `../nacserver/`
//! (which was run end-to-end against the real binary + a real Mac identity and
//! produced valid 389-byte validation data). Maps the untouched
//! `IMDAppleServices` x86_64 slice under Unicorn, redirects every imported
//! function pointer to a 1-byte `ret` trampoline in a hook page, and dispatches
//! those trampolines to the handlers in `hooks.rs`.
//!
//! NOTE: building this requires `libunicorn` (the `unicorn-engine` crate builds
//! it via cmake). It cannot be exercised without a copy of Apple's binary, which
//! we neither ship nor extract — the operator supplies it from their own Mac.

use std::sync::{Arc, Mutex};

use unicorn_engine::unicorn_const::{Arch, Mode, Prot};
use unicorn_engine::{RegisterX86, Unicorn};

use crate::error::{AbsintheError, Result};
use crate::hardware::HardwareConfig;
use crate::hooks::HookState;
use crate::macho::{self, MachOImage};

const PAGE: u64 = 0x1000;
const STACK_BASE: u64 = 0x0030_0000;
const STACK_SIZE: u64 = 0x0010_0000;
pub(crate) const HEAP_BASE: u64 = 0x0040_0000;
pub(crate) const HEAP_SIZE: u64 = 0x0010_0000;
const STOP_ADDR: u64 = 0x0090_0000;
pub(crate) const HOOK_BASE: u64 = 0x00D0_0000;
const HOOK_SIZE: u64 = PAGE;

const ARG_REGS: [RegisterX86; 6] = [
    RegisterX86::RDI,
    RegisterX86::RSI,
    RegisterX86::RDX,
    RegisterX86::RCX,
    RegisterX86::R8,
    RegisterX86::R9,
];

fn round_up(n: u64, page: u64) -> u64 {
    (n + page - 1) & !(page - 1)
}

/// A live emulation session bound to one hardware identity.
pub struct NacSession {
    // `Unicorn` borrows its user-data; we keep the state behind Arc<Mutex> so
    // the code-hook closure and our methods can both reach it.
    state: Arc<Mutex<HookState>>,
    // The emulator is created per-session; `'static` data via Arc.
    uc: Unicorn<'static, ()>,
}

impl NacSession {
    /// Map the binary, wire the hooks, and return a ready session.
    pub fn load(binary: &[u8], hw: &HardwareConfig) -> Result<NacSession> {
        let image = MachOImage::from_fat(binary)?;
        if !image.nac_prologues_ok() {
            return Err(AbsintheError::BinaryMismatch {
                expected: macho::EXPECTED_SHA1.to_string(),
                got: "prologue check failed".to_string(),
            });
        }

        let mut uc = Unicorn::new(Arch::X86, Mode::MODE_64)
            .map_err(|e| AbsintheError::Emulation(format!("unicorn init: {e:?}")))?;

        // Map + write the binary at vmaddr 0.
        let bin_size = round_up(image.slice.len() as u64, PAGE);
        map(&mut uc, 0, bin_size)?;
        write(&mut uc, 0, &image.slice)?;

        // Scratch regions.
        map(&mut uc, STACK_BASE, STACK_SIZE)?;
        map(&mut uc, HEAP_BASE, HEAP_SIZE)?;
        map(&mut uc, STOP_ADDR, PAGE)?;
        write(&mut uc, STOP_ADDR, &vec![0xC3u8; PAGE as usize])?;
        map(&mut uc, HOOK_BASE, HOOK_SIZE)?;
        write(&mut uc, HOOK_BASE, &vec![0xC3u8; HOOK_SIZE as usize])?;

        // Assign each imported symbol a 1-byte-spaced trampoline in the hook
        // page and redirect every GOT slot to it.
        let mut symbols: Vec<String> = Vec::new();
        let mut seen = std::collections::HashSet::new();
        for b in &image.binds {
            if seen.insert(b.symbol.clone()) {
                symbols.push(b.symbol.clone());
            }
        }
        if symbols.len() as u64 > HOOK_SIZE {
            return Err(AbsintheError::Emulation(
                "more imports than the hook page can address".into(),
            ));
        }
        let mut tramp = std::collections::HashMap::new();
        let mut tramp_to_sym = std::collections::HashMap::new();
        for (i, sym) in symbols.iter().enumerate() {
            let addr = HOOK_BASE + i as u64;
            tramp.insert(sym.clone(), addr);
            tramp_to_sym.insert(addr, sym.clone());
        }
        for b in &image.binds {
            let addr = tramp[&b.symbol];
            write(&mut uc, b.address, &addr.to_le_bytes())?;
        }

        let state = Arc::new(Mutex::new(HookState::new(hw)));

        // Install the dispatcher over the hook page.
        {
            let state = Arc::clone(&state);
            let tramp_to_sym = tramp_to_sym.clone();
            uc.add_code_hook(HOOK_BASE, HOOK_BASE + HOOK_SIZE, move |uc, address, _size| {
                if let Some(sym) = tramp_to_sym.get(&address) {
                    let mut st = state.lock().unwrap();
                    if let Err(e) = crate::hooks::dispatch(uc, &mut st, sym) {
                        st.fault = Some(e);
                        uc.emu_stop().ok();
                    }
                }
            })
            .map_err(|e| AbsintheError::Emulation(format!("add_code_hook: {e:?}")))?;
        }

        Ok(NacSession { state, uc })
    }

    /// `nac_init(cert)` → request blob to POST to id-initialize-validation.
    pub fn nac_init(&mut self, cert: &[u8]) -> Result<Vec<u8>> {
        let cert_addr = self.malloc(cert.len() as u64)?;
        write(&mut self.uc, cert_addr, cert)?;
        let out_ctx = self.malloc(8)?;
        let out_req = self.malloc(8)?;
        let out_len = self.malloc(8)?;
        let ret = self.call(
            macho::NAC_INIT_OFF,
            &[cert_addr, cert.len() as u64, out_ctx, out_req, out_len],
        )?;
        self.check(ret, "nac_init")?;
        self.state.lock().unwrap().validation_ctx =
            u64::from_le_bytes(read8(&self.uc, out_ctx)?);
        let req_ptr = u64::from_le_bytes(read8(&self.uc, out_req)?);
        let req_len = u64::from_le_bytes(read8(&self.uc, out_len)?);
        read_vec(&self.uc, req_ptr, req_len as usize)
    }

    /// `nac_key_establishment(session_info)`.
    pub fn nac_key_establishment(&mut self, response: &[u8]) -> Result<()> {
        let ctx = self.state.lock().unwrap().validation_ctx;
        let resp_addr = self.malloc(response.len() as u64)?;
        write(&mut self.uc, resp_addr, response)?;
        let ret = self.call(
            macho::NAC_KEY_ESTABLISHMENT_OFF,
            &[ctx, resp_addr, response.len() as u64],
        )?;
        self.check(ret, "nac_key_establishment")
    }

    /// `nac_sign()` → the validation-data blob.
    pub fn sign_data(&mut self) -> Result<Vec<u8>> {
        let ctx = self.state.lock().unwrap().validation_ctx;
        let out_data = self.malloc(8)?;
        let out_len = self.malloc(8)?;
        let ret = self.call(macho::NAC_SIGN_OFF, &[ctx, 0, 0, out_data, out_len])?;
        self.check(ret, "nac_sign")?;
        let data_ptr = u64::from_le_bytes(read8(&self.uc, out_data)?);
        let data_len = u64::from_le_bytes(read8(&self.uc, out_len)?);
        read_vec(&self.uc, data_ptr, data_len as usize)
    }

    fn malloc(&mut self, size: u64) -> Result<u64> {
        let mut st = self.state.lock().unwrap();
        let addr = st.heap;
        st.heap = round_up(st.heap + size, 8);
        if st.heap >= HEAP_BASE + HEAP_SIZE {
            return Err(AbsintheError::Emulation("emulated heap exhausted".into()));
        }
        Ok(addr)
    }

    fn call(&mut self, offset: u64, args: &[u64]) -> Result<u64> {
        let mut rsp = (STACK_BASE + STACK_SIZE) & !0xF;
        rsp -= 8;
        write(&mut self.uc, rsp, &STOP_ADDR.to_le_bytes())?;
        self.uc
            .reg_write(RegisterX86::RSP, rsp)
            .map_err(|e| AbsintheError::Emulation(format!("rsp: {e:?}")))?;
        for (i, &v) in args.iter().enumerate() {
            self.uc
                .reg_write(ARG_REGS[i], v)
                .map_err(|e| AbsintheError::Emulation(format!("arg{i}: {e:?}")))?;
        }
        self.uc
            .emu_start(offset, STOP_ADDR, 0, 0)
            .map_err(|e| AbsintheError::Emulation(format!("emu_start @ {offset:#x}: {e:?}")))?;
        // Surface a hook-raised fault, if any.
        if let Some(err) = self.state.lock().unwrap().fault.take() {
            return Err(err);
        }
        self.uc
            .reg_read(RegisterX86::RAX)
            .map_err(|e| AbsintheError::Emulation(format!("rax: {e:?}")))
    }

    fn check(&self, ret: u64, what: &str) -> Result<()> {
        if ret != 0 {
            let n = (ret & 0xFFFF_FFFF) as u32 as i32;
            return Err(AbsintheError::Emulation(format!(
                "{what} returned {n} ({:#x})",
                n as u32
            )));
        }
        Ok(())
    }
}

// ---- Unicorn helpers --------------------------------------------------------

fn map(uc: &mut Unicorn<'_, ()>, addr: u64, size: u64) -> Result<()> {
    uc.mem_map(addr, size, Prot::ALL)
        .map_err(|e| AbsintheError::Emulation(format!("mem_map {addr:#x}: {e:?}")))
}
fn write(uc: &mut Unicorn<'_, ()>, addr: u64, data: &[u8]) -> Result<()> {
    uc.mem_write(addr, data)
        .map_err(|e| AbsintheError::Emulation(format!("mem_write {addr:#x}: {e:?}")))
}
fn read_vec(uc: &Unicorn<'_, ()>, addr: u64, len: usize) -> Result<Vec<u8>> {
    uc.mem_read_as_vec(addr, len)
        .map_err(|e| AbsintheError::Emulation(format!("mem_read {addr:#x}: {e:?}")))
}
fn read8(uc: &Unicorn<'_, ()>, addr: u64) -> Result<[u8; 8]> {
    let v = read_vec(uc, addr, 8)?;
    Ok(v.try_into().unwrap())
}
