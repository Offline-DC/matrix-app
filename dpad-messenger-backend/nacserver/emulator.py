"""Clean-room x86-64 emulation harness for the nac functions.

Maps the untouched ``IMDAppleServices`` x86_64 slice under Unicorn, redirects
every imported-function pointer to a 1-byte ``ret`` trampoline in a dedicated
hook page, and dispatches those trampolines to Python handlers. This is the
mechanism that lets us run Apple's obfuscated validation-data code without
understanding it, feeding it a spoofed hardware identity through the IOKit
hooks (see ``nac.py``).

No third-party emulator glue is used; only the ``unicorn`` CPU emulator itself.
"""
from unicorn import Uc, UC_ARCH_X86, UC_MODE_64, UC_HOOK_CODE
from unicorn.x86_const import (
    UC_X86_REG_RAX, UC_X86_REG_RDI, UC_X86_REG_RSI, UC_X86_REG_RDX,
    UC_X86_REG_RCX, UC_X86_REG_R8, UC_X86_REG_R9, UC_X86_REG_RSP,
)

from . import macho

PAGE = 0x1000
STACK_BASE, STACK_SIZE = 0x00300000, 0x00100000
HEAP_BASE, HEAP_SIZE = 0x00400000, 0x00100000
STOP_ADDR = 0x00900000          # a page full of 0xC3 (ret): halts emu_start
HOOK_BASE, HOOK_SIZE = 0x00D00000, PAGE

_ARG_REGS = [UC_X86_REG_RDI, UC_X86_REG_RSI, UC_X86_REG_RDX,
             UC_X86_REG_RCX, UC_X86_REG_R8, UC_X86_REG_R9]


def _round_up(n, page=PAGE):
    return (n + page - 1) & ~(page - 1)


class UnhookedImport(Exception):
    pass


class Emulator:
    def __init__(self, slice_bytes: bytes):
        self.macho = macho.MachO(slice_bytes)
        if not self.macho.prologues_ok():
            raise ValueError("nac prologues missing — wrong IMDAppleServices build")
        self.uc = Uc(UC_ARCH_X86, UC_MODE_64)
        self._heap = HEAP_BASE
        self._hooks = {}            # symbol -> handler
        self._tramp_to_sym = {}     # trampoline address -> symbol
        self._map_binary(slice_bytes)
        self._map_scratch()

    # ---- memory setup -------------------------------------------------------

    def _map_binary(self, slice_bytes):
        size = _round_up(len(slice_bytes))
        self.uc.mem_map(0, size)
        self.uc.mem_write(0, slice_bytes)

    def _map_scratch(self):
        self.uc.mem_map(STACK_BASE, STACK_SIZE)
        self.uc.mem_map(HEAP_BASE, HEAP_SIZE)
        self.uc.mem_map(STOP_ADDR, PAGE)
        self.uc.mem_write(STOP_ADDR, b"\xc3" * PAGE)     # ret sled
        self.uc.mem_map(HOOK_BASE, HOOK_SIZE)
        self.uc.mem_write(HOOK_BASE, b"\xc3" * HOOK_SIZE)  # every trampoline = ret

    # ---- hook wiring --------------------------------------------------------

    def setup(self, hooks: dict):
        """Assign each imported symbol a trampoline and redirect its GOT slots."""
        self._hooks = hooks
        symbols = []
        seen = set()
        for _addr, sym in self.macho.binds:
            if sym not in seen:
                seen.add(sym)
                symbols.append(sym)
        if len(symbols) > HOOK_SIZE:
            raise RuntimeError("more imports than the hook page can address")
        tramp = {}
        for idx, sym in enumerate(symbols):
            addr = HOOK_BASE + idx
            tramp[sym] = addr
            self._tramp_to_sym[addr] = sym
        for slot_addr, sym in self.macho.binds:
            self.uc.mem_write(slot_addr, tramp[sym].to_bytes(8, "little"))
        self.uc.hook_add(UC_HOOK_CODE, self._dispatch, begin=HOOK_BASE,
                         end=HOOK_BASE + HOOK_SIZE)

    def _dispatch(self, uc, address, size, _user):
        sym = self._tramp_to_sym.get(address)
        if sym is None:
            return
        handler = self._hooks.get(sym)
        if handler is None:
            # Stop and surface which import drifted in.
            uc.emu_stop()
            raise UnhookedImport(sym)
        ret = handler(self)
        if ret is not None:
            self.uc.reg_write(UC_X86_REG_RAX, ret & 0xFFFFFFFFFFFFFFFF)
        # The 0xC3 at `address` executes next and returns to the caller.

    # ---- calling convention -------------------------------------------------

    def a(self, n: int) -> int:
        """Read the n-th integer argument (System V AMD64: rdi, rsi, ...)."""
        return self.uc.reg_read(_ARG_REGS[n])

    def malloc(self, size: int) -> int:
        addr = self._heap
        self._heap = _round_up(self._heap + size, 8)
        if self._heap >= HEAP_BASE + HEAP_SIZE:
            raise RuntimeError("emulated heap exhausted")
        return addr

    def read(self, addr: int, size: int) -> bytes:
        return bytes(self.uc.mem_read(addr, size))

    def write(self, addr: int, data: bytes):
        self.uc.mem_write(addr, bytes(data))

    def call(self, offset: int, args) -> int:
        """Invoke a function at `offset` in the slice with SysV args; return RAX."""
        if len(args) > 6:
            raise NotImplementedError("stack args not needed for nac")
        # 16-byte align the stack, then push the fake return address so the
        # callee's `ret` lands on the STOP ret-sled and halts emu_start.
        rsp = (STACK_BASE + STACK_SIZE) & ~0xF
        rsp -= 8
        self.uc.mem_write(rsp, STOP_ADDR.to_bytes(8, "little"))
        self.uc.reg_write(UC_X86_REG_RSP, rsp)
        for i, val in enumerate(args):
            self.uc.reg_write(_ARG_REGS[i], val & 0xFFFFFFFFFFFFFFFF)
        self.uc.emu_start(offset, STOP_ADDR)
        return self.uc.reg_read(UC_X86_REG_RAX)
