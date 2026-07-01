"""The nac engine: the three validation-data calls plus the import hooks that
feed Apple's obfuscated code a spoofed Mac hardware identity.

Clean-room reimplementation. The hook *behaviours* are dictated by the binary's
ABI expectations (facts about how it reads IOKit / CoreFoundation), not copied
from any GPL/SSPL source. Mirrors the Rust crate's ``hooks.rs`` / ``nac.rs``.
"""
import logging
import random
import struct

from . import macho
from .emulator import Emulator
from .hardware import MacOSConfig

log = logging.getLogger("nac")

CERT_URL = "http://static.ess.apple.com/identity/validation/cert-1.0.plist"
INIT_VALIDATION_URL = (
    "https://identity.ess.apple.com/WebObjects/TDIdentityService.woa/wa/initializeValidation"
)
# When a data-symbol constant (e.g. kDADiskDescriptionVolumeUUIDKey) is read
# through the 0xC3-filled hook page, the 8 bytes come back as this sentinel.
_C3_SENTINEL = 0xC3C3C3C3C3C3C3C3


class NacEngine:
    def __init__(self, slice_bytes: bytes, config: MacOSConfig):
        self.emu = Emulator(slice_bytes)
        self.fake_iokit = config.inner.iokit()
        self.root_disk_uuid = config.inner.root_disk_uuid
        self.cf = []                 # CF object table; handle == index + 1
        self._eth_iter = False
        self.emu.setup(self._hooks())

    # ---- CF helpers ---------------------------------------------------------

    def _cf_new(self, obj) -> int:
        self.cf.append(obj)
        return len(self.cf)          # 1-based; 0 == NULL

    def _cf_get(self, handle):
        return self.cf[handle - 1]

    def _cfstr_ptr(self, emu, ptr) -> str:
        isa, flags, str_ptr, length = struct.unpack("<QQQQ", emu.read(ptr, 32))
        return emu.read(str_ptr, length).decode("utf-8")

    def _cstr_ptr(self, emu, ptr) -> str:
        return emu.read(ptr, 256).split(b"\x00")[0].decode("utf-8")

    def _maybe(self, val):
        """A dict key/value is either a raw string, a raw pointer, or a handle."""
        if isinstance(val, str):
            return val
        if val > len(self.cf):
            return val
        return self._cf_get(val)

    # ---- hooks --------------------------------------------------------------

    def _hooks(self):
        return {
            # libc / memory
            "_malloc": lambda e: e.malloc(e.a(0)),
            "_free": lambda e: 0,
            "___stack_chk_guard": lambda e: 0,
            "_sysctlbyname": lambda e: 0,
            "_memcpy": self._memcpy,
            "___memset_chk": self._memset_chk,
            "___bzero": self._bzero,
            "_arc4random": lambda e: random.randint(0, 0xFFFFFFFF),
            # CoreFoundation
            "_kCFAllocatorDefault": lambda e: 0,
            "_kCFBooleanTrue": lambda e: 0,
            "_CFRelease": lambda e: 0,
            "_CFGetTypeID": self._cf_get_type_id,
            "_CFStringGetTypeID": lambda e: 2,
            "_CFDataGetTypeID": lambda e: 1,
            "_CFDataGetLength": lambda e: len(self._cf_get(e.a(0))),
            "_CFDataGetBytes": self._cfdata_get_bytes,
            "_CFDictionaryCreateMutable": lambda e: self._cf_new({}),
            "_CFDictionaryGetValue": self._cfdict_get,
            "_CFDictionarySetValue": self._cfdict_set,
            "_CFStringGetLength": lambda e: len(self._cf_get(e.a(0))),
            "_CFStringGetCString": self._cfstr_get_cstring,
            # CFStringGetMaximumSizeForEncoding(length, encoding) -> length
            "_CFStringGetMaximumSizeForEncoding": lambda e: e.a(0),
            # CFUUIDCreateString(allocator, uuid) -> uuid (pass the handle back)
            "_CFUUIDCreateString": lambda e: e.a(1),
            # IOKit
            "_kIOMasterPortDefault": lambda e: 0,
            "_IORegistryEntryFromPath": lambda e: 1,
            "_IORegistryEntryCreateCFProperty": self._iokit_property,
            "_IOServiceMatching": self._io_service_matching,
            "_IOServiceGetMatchingService": lambda e: 92,
            "_IOServiceGetMatchingServices": self._io_get_matching_services,
            "_IOIteratorNext": self._io_iterator_next,
            "_IORegistryEntryGetParentEntry": self._io_parent,
            "_IOObjectRelease": lambda e: 0,
            # DiskArbitration (root volume UUID)
            "_DASessionCreate": lambda e: 201,
            "_DADiskCreateFromBSDName": lambda e: 202,
            "_kDADiskDescriptionVolumeUUIDKey": lambda e: 0,
            "_DADiskCopyDescription": self._da_copy_description,
            "_statfs$INODE64": lambda e: 0,
        }

    def _memcpy(self, e):
        dest, src, n = e.a(0), e.a(1), e.a(2)
        e.write(dest, e.read(src, n))
        return dest

    def _memset_chk(self, e):
        dest, c, n = e.a(0), e.a(1), e.a(2)
        e.write(dest, bytes([c & 0xFF]) * n)
        return dest

    def _bzero(self, e):
        e.write(e.a(0), b"\x00" * e.a(1))
        return 0

    def _cf_get_type_id(self, e):
        obj = self._cf_get(e.a(0))
        return 1 if isinstance(obj, bytes) else 2  # 1 = CFData, 2 = CFString

    def _cfdata_get_bytes(self, e):
        obj = self._cf_get(e.a(0))
        start, end, buf = e.a(1), e.a(2), e.a(3)
        chunk = obj[start:end]
        e.write(buf, chunk)
        return len(chunk)

    def _cfstr_get_cstring(self, e):
        s = self._cf_get(e.a(0))
        e.write(e.a(1), s.encode("utf-8"))
        return 1  # success (Boolean)

    def _cfdict_get(self, e):
        d = self._cf_get(e.a(0))
        key = e.a(1)
        if key == _C3_SENTINEL:
            key = "DADiskDescriptionVolumeUUIDKey"
        else:
            key = self._maybe(key)
        return self._cf_new(d[key])

    def _cfdict_set(self, e):
        d = self._cf_get(e.a(0))
        d[self._maybe(e.a(1))] = self._maybe(e.a(2))
        return 0

    def _iokit_property(self, e):
        key = self._cfstr_ptr(e, e.a(1))
        if key in self.fake_iokit:
            log.debug("IOKit read %s", key)
            return self._cf_new(self.fake_iokit[key])
        log.debug("IOKit read %s -> (none)", key)
        return 0

    def _io_service_matching(self, e):
        name = self._cstr_ptr(e, e.a(0))
        d = self._cf_new({})
        self.cf[d - 1]["IOProviderClass"] = self._cf_new(name)
        return d

    def _io_get_matching_services(self, e):
        self._eth_iter = True
        e.write(e.a(2), bytes([93]))     # iterator handle
        return 0

    def _io_iterator_next(self, e):
        if self._eth_iter:               # yield the one ethernet service, once
            self._eth_iter = False
            return 94
        return 0

    def _io_parent(self, e):
        e.write(e.a(2), bytes([e.a(0) + 100]))
        return 0

    def _da_copy_description(self, e):
        d = self._cf_new({})
        self.cf[d - 1]["DADiskDescriptionVolumeUUIDKey"] = self.root_disk_uuid
        return d

    # ---- the three nac calls -----------------------------------------------

    @staticmethod
    def _check(ret, what):
        if ret != 0:
            n = ret & 0xFFFFFFFF
            n = (n ^ 0x80000000) - 0x80000000
            raise RuntimeError(f"{what} failed: {n} ({n & 0xffffffff:#x})")

    def nac_init(self, cert: bytes):
        e = self.emu
        cert_addr = e.malloc(len(cert)); e.write(cert_addr, cert)
        out_ctx, out_req, out_len = e.malloc(8), e.malloc(8), e.malloc(8)
        ret = e.call(macho.NAC_INIT, [cert_addr, len(cert), out_ctx, out_req, out_len])
        self._check(ret, "nac_init")
        ctx = int.from_bytes(e.read(out_ctx, 8), "little")
        req_ptr = int.from_bytes(e.read(out_req, 8), "little")
        req_len = int.from_bytes(e.read(out_len, 8), "little")
        return ctx, e.read(req_ptr, req_len)

    def nac_key_establishment(self, ctx: int, response: bytes):
        e = self.emu
        resp_addr = e.malloc(len(response)); e.write(resp_addr, response)
        ret = e.call(macho.NAC_KEY_ESTABLISHMENT, [ctx, resp_addr, len(response)])
        self._check(ret, "nac_key_establishment")

    def nac_sign(self, ctx: int) -> bytes:
        e = self.emu
        out_data, out_len = e.malloc(8), e.malloc(8)
        ret = e.call(macho.NAC_SIGN, [ctx, 0, 0, out_data, out_len])
        self._check(ret, "nac_sign")
        data_ptr = int.from_bytes(e.read(out_data, 8), "little")
        data_len = int.from_bytes(e.read(out_len, 8), "little")
        return e.read(data_ptr, data_len)


# ---- network round-trips + top-level driver --------------------------------

def get_cert(session=None) -> bytes:
    import plistlib
    import requests
    resp = (session or requests).get(CERT_URL, timeout=30)
    resp.raise_for_status()
    return plistlib.loads(resp.content)["cert"]


def get_session_info(request: bytes, session=None) -> bytes:
    import plistlib
    import requests
    body = plistlib.dumps({"session-info-request": request})
    # Apple's identity.ess.apple.com presents a chain many trust stores lack;
    # community clients (pypush, rustpush) disable verification for THIS call.
    resp = (session or requests).post(
        INIT_VALIDATION_URL, data=body, timeout=30, verify=False
    )
    resp.raise_for_status()
    return plistlib.loads(resp.content)["session-info"]


def generate_validation_data(config: MacOSConfig, binary_bytes: bytes) -> bytes:
    """Full pipeline → raw validation-data bytes for `config`'s identity."""
    slice_bytes = macho.x86_64_slice(binary_bytes)
    engine = NacEngine(slice_bytes, config)
    ctx, request = engine.nac_init(get_cert())
    log.info("nac_init: %d-byte request", len(request))
    session_info = get_session_info(bytes(request))
    log.info("initializeValidation: %d-byte session-info", len(session_info))
    engine.nac_key_establishment(ctx, session_info)
    data = bytes(engine.nac_sign(ctx))
    log.info("nac_sign: %d-byte validation-data", len(data))
    return data
