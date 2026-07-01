"""Compact fat/Mach-O loader + classic dyld bind-opcode interpreter.

Clean-room implementation (no third-party Mach-O parser). Only does what the
nac emulator needs: pull the x86_64 slice, enumerate LC_SEGMENT_64 load
commands, and walk the LC_DYLD_INFO(_ONLY) bind + lazy-bind streams to learn
which address each imported symbol's pointer slot lives at.

Validated against IMDAppleServices sha1 e1181ccad82e6629d52c6a006645ad87ee59bd13.
"""
import struct

CPU_TYPE_X86_64 = 0x01000007
MH_MAGIC_64 = 0xFEEDFACF
LC_SEGMENT_64 = 0x19
LC_DYLD_INFO = 0x22
LC_DYLD_INFO_ONLY = 0x80000022
PTR = 8

# nac function offsets *within the x86_64 slice*.
NAC_INIT = 0xB1DB0
NAC_KEY_ESTABLISHMENT = 0xB1DD0
NAC_SIGN = 0xB1DF0
EXPECTED_SHA1 = "e1181ccad82e6629d52c6a006645ad87ee59bd13"


def x86_64_slice(fat_bytes: bytes) -> bytes:
    """Return the x86_64 slice of a fat binary (or the input if already thin)."""
    (magic,) = struct.unpack_from(">I", fat_bytes, 0)
    if magic == 0xCAFEBABE:
        (nfat,) = struct.unpack_from(">I", fat_bytes, 4)
        for i in range(nfat):
            cputype, _sub, offset, size, _align = struct.unpack_from(">IIIII", fat_bytes, 8 + i * 20)
            if cputype == CPU_TYPE_X86_64:
                return fat_bytes[offset:offset + size]
        raise ValueError("no x86_64 slice in fat binary")
    (le,) = struct.unpack_from("<I", fat_bytes, 0)
    if le == MH_MAGIC_64:
        return fat_bytes
    raise ValueError(f"not a fat or 64-bit Mach-O (magic {magic:#x})")


def _uleb(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, i
        s += 7


class MachO:
    def __init__(self, slice_bytes: bytes):
        if struct.unpack_from("<I", slice_bytes, 0)[0] != MH_MAGIC_64:
            raise ValueError("slice is not MH_MAGIC_64")
        self.data = slice_bytes
        self.segments = []          # list of (name, vmaddr)
        self.binds = []             # list of (address, symbol)
        self._parse()

    def _parse(self):
        (ncmds,) = struct.unpack_from("<I", self.data, 16)
        p = 32
        dyld = None
        for _ in range(ncmds):
            cmd, cmdsize = struct.unpack_from("<II", self.data, p)
            if cmdsize == 0:
                raise ValueError("zero-size load command")
            if cmd == LC_SEGMENT_64:
                name = self.data[p + 8:p + 24].split(b"\x00")[0].decode()
                (vmaddr,) = struct.unpack_from("<Q", self.data, p + 24)
                self.segments.append((name, vmaddr))
            elif cmd in (LC_DYLD_INFO, LC_DYLD_INFO_ONLY):
                vals = struct.unpack_from("<10I", self.data, p + 8)
                dyld = dict(bind=(vals[2], vals[3]), lazy=(vals[6], vals[7]))
            p += cmdsize
        if dyld is None:
            raise ValueError("no LC_DYLD_INFO")
        for off, size in (dyld["bind"], dyld["lazy"]):
            if size:
                self._walk_binds(self.data[off:off + size])

    def _walk_binds(self, s: bytes):
        i = 0
        seg = 0
        seg_off = 0
        sym = None
        n = len(s)
        while i < n:
            op = s[i]; i += 1
            opcode, imm = op & 0xF0, op & 0x0F
            if opcode == 0x00:                        # DONE
                pass
            elif opcode in (0x10, 0x30):              # dylib ordinal imm/special
                pass
            elif opcode == 0x20:                      # dylib ordinal uleb
                _, i = _uleb(s, i)
            elif opcode == 0x40:                      # symbol name
                j = s.index(b"\x00", i)
                sym = s[i:j].decode(); i = j + 1
            elif opcode == 0x50:                      # type imm
                pass
            elif opcode == 0x60:                      # addend sleb
                _, i = _uleb(s, i)
            elif opcode == 0x70:                      # set segment + offset
                seg = imm; seg_off, i = _uleb(s, i)
            elif opcode == 0x80:                      # add addr uleb
                v, i = _uleb(s, i); seg_off += v
            elif opcode == 0x90:                      # do bind
                self._emit(seg, seg_off, sym); seg_off += PTR
            elif opcode == 0xA0:                      # do bind + add addr uleb
                self._emit(seg, seg_off, sym); v, i = _uleb(s, i); seg_off += PTR + v
            elif opcode == 0xB0:                      # do bind + add addr imm scaled
                self._emit(seg, seg_off, sym); seg_off += PTR + imm * PTR
            elif opcode == 0xC0:                      # do bind uleb times skipping uleb
                count, i = _uleb(s, i); skip, i = _uleb(s, i)
                for _ in range(count):
                    self._emit(seg, seg_off, sym); seg_off += PTR + skip
            else:
                raise ValueError(f"unknown bind opcode {opcode:#x}")

    def _emit(self, seg, seg_off, sym):
        if sym is None:
            raise ValueError("DO_BIND without symbol")
        self.binds.append((self.segments[seg][1] + seg_off, sym))

    def prologues_ok(self) -> bool:
        return all(
            self.data[o:o + 4] == b"\x55\x48\x89\xe5"
            for o in (NAC_INIT, NAC_KEY_ESTABLISHMENT, NAC_SIGN)
        )
