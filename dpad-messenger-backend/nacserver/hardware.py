"""Parser for the OABS "dumb file" (a serialized MacOSConfig) and the mapping
from its fields to the IOKit property reads the emulated binary performs.

Wire format (reverse-engineered, byte-exact): 5-byte magic ``OABS\\0`` followed
by a protobuf message. Field numbers recovered from a real Mac17,2 capture and
cross-checked against rustpush's MacOSConfig/HardwareConfig and pypush's IOKit
key set. Mirrors ``absinthe/src/hardware.rs`` in the Rust crate.
"""
import base64

OABS_MAGIC = b"OABS\x00"
# NVRAM (IODeviceTree:/options) property GUID prefix for ROM / MLB.
NVRAM_GUID = "4D1EDE05-38C7-4A6A-9CC6-4BCCA8B38C14"


def _read_varint(b, i):
    r = s = 0
    while True:
        x = b[i]; i += 1
        r |= (x & 0x7F) << s
        if not x & 0x80:
            return r, i
        s += 7


def _read_message(b):
    """Yield (field_number, wire_type, value) for a protobuf message body."""
    i = 0
    n = len(b)
    while i < n:
        tag, i = _read_varint(b, i)
        fnum, wt = tag >> 3, tag & 7
        if wt == 0:
            v, i = _read_varint(b, i)
            yield fnum, 0, v
        elif wt == 2:
            ln, i = _read_varint(b, i)
            yield fnum, 2, b[i:i + ln]
            i += ln
        else:
            raise ValueError(f"unsupported wire type {wt}")


class HardwareConfig:
    """The captured Mac hardware fingerprint (OABS inner message)."""

    # field_number -> attribute name; b'' fields stay bytes, others decode utf-8
    _STR = {1: "product_name", 3: "platform_serial_number", 4: "platform_uuid",
            5: "root_disk_uuid", 6: "board_id", 7: "os_build_num", 13: "mlb"}
    _BYTES = {2: "rom", 8: "gq3489ugfi", 9: "fyp98tpgj", 10: "kbjfrfpoju",
              11: "mac_address", 12: "oycqazlotndm", 14: "abkpld1ecmni"}

    def __init__(self):
        for a in self._STR.values():
            setattr(self, a, "")
        for a in self._BYTES.values():
            setattr(self, a, b"")

    @classmethod
    def decode(cls, body: bytes) -> "HardwareConfig":
        hw = cls()
        for fnum, wt, val in _read_message(body):
            if fnum in cls._STR:
                setattr(hw, cls._STR[fnum], val.decode())
            elif fnum in cls._BYTES:
                setattr(hw, cls._BYTES[fnum], bytes(val))
        return hw

    def iokit(self) -> dict:
        """The FAKE_DATA["iokit"] table: exact IOKit property name -> value.

        CFString reads (serial/UUID) are returned as ``str``; CFData reads
        (NVRAM, MAC, obfuscated keys, and the NUL-terminated board-id/
        product-name C strings) are returned as ``bytes``.
        """
        return {
            "IOPlatformSerialNumber": self.platform_serial_number,
            "IOPlatformUUID": self.platform_uuid,
            "board-id": self.board_id.encode() + b"\x00",
            "product-name": self.product_name.encode() + b"\x00",
            f"{NVRAM_GUID}:ROM": self.rom,
            f"{NVRAM_GUID}:MLB": self.mlb.encode(),
            "IOMACAddress": self.mac_address,
            "Gq3489ugfi": self.gq3489ugfi,
            "Fyp98tpgj": self.fyp98tpgj,
            "kbjfrfpoJU": self.kbjfrfpoju,
            "oycqAZloTNDm": self.oycqazlotndm,
            "abKPld1EcMni": self.abkpld1ecmni,
        }


class MacOSConfig:
    """The full dumb file: a HardwareConfig plus OS/client metadata."""

    def __init__(self):
        self.inner = HardwareConfig()
        self.version = ""
        self.protocol_version = 0
        self.device_id = ""
        self.icloud_ua = ""
        self.aoskit_version = ""

    @classmethod
    def parse(cls, raw: bytes) -> "MacOSConfig":
        if raw[:5] != OABS_MAGIC:
            raise ValueError("missing OABS magic prefix")
        cfg = cls()
        for fnum, wt, val in _read_message(raw[5:]):
            if fnum == 1:
                cfg.inner = HardwareConfig.decode(val)
            elif fnum == 2:
                cfg.version = val.decode()
            elif fnum == 3:
                cfg.protocol_version = val
            elif fnum == 4:
                cfg.device_id = val.decode()
            elif fnum == 5:
                cfg.icloud_ua = val.decode()
            elif fnum == 6:
                cfg.aoskit_version = val.decode()
        if not cfg.inner.platform_serial_number:
            raise ValueError("no platform serial number in dumb file")
        return cfg

    @classmethod
    def load(cls, path: str) -> "MacOSConfig":
        """Load a dumb file that is base64-encoded OABS (as stored on disk)."""
        with open(path, "rb") as f:
            data = f.read().strip()
        try:
            raw = base64.b64decode(data, validate=True)
        except Exception:
            raw = data  # already raw OABS
        return cls.parse(raw)
