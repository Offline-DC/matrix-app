#!/usr/bin/env python3
"""
Reference/validator for the Kotlin `CafAudio` Opus path.

Remuxes an Apple CAF containing Opus (iMessage voice memo) into Ogg/Opus, with NO
re-encode — the exact algorithm CafAudio.kt uses (same CAF parsing, same Ogg pages,
same Ogg CRC). If the output plays, the Kotlin implementation is correct.

    python3 caf_to_opus.py voice.caf out.opus
    afplay out.opus        # or: ffprobe out.opus

Exit 2 = input isn't CAF/Opus (nothing written).
"""
import struct
import sys


def _crc_table():
    t = []
    for i in range(256):
        r = (i << 24) & 0xFFFFFFFF
        for _ in range(8):
            r = ((r << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if r & 0x80000000 else (r << 1) & 0xFFFFFFFF
        t.append(r)
    return t


OGG_CRC = _crc_table()


def ogg_crc(data: bytes) -> int:
    crc = 0
    for b in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ OGG_CRC[((crc >> 24) ^ b) & 0xFF]
    return crc & 0xFFFFFFFF


def ogg_page(serial, seq, header_type, granule, body, packet_sizes):
    seg = []
    for sz in packet_sizes:
        n = sz
        while n >= 255:
            seg.append(255)
            n -= 255
        seg.append(n)
    page = bytearray(27 + len(seg) + len(body))
    page[0:4] = b"OggS"
    page[4] = 0
    page[5] = header_type
    struct.pack_into("<q", page, 6, granule)
    struct.pack_into("<I", page, 14, serial & 0xFFFFFFFF)
    struct.pack_into("<I", page, 18, seq & 0xFFFFFFFF)
    page[26] = len(seg)
    for i, s in enumerate(seg):
        page[27 + i] = s
    page[27 + len(seg):] = body
    struct.pack_into("<I", page, 22, ogg_crc(bytes(page)))
    return bytes(page)


def opus_packet_samples_48k(b, off, size):
    """Exact 48 kHz sample count for one Opus packet, from its TOC byte(s)."""
    if size < 1:
        return 960
    toc = b[off]
    config = toc >> 3
    if config < 12:      # SILK: 10/20/40/60 ms
        per_frame = (480, 960, 1920, 2880)[config & 0x3]
    elif config < 16:    # Hybrid: 10/20 ms
        per_frame = (480, 960)[config & 0x1]
    else:                # CELT: 2.5/5/10/20 ms
        per_frame = (120, 240, 480, 960)[config & 0x3]
    code = toc & 0x3
    if code == 0:
        frames = 1
    elif code in (1, 2):
        frames = 2
    else:                # code 3: M frames in the next byte
        frames = (b[off + 1] & 0x3F) if size >= 2 else 1
    return per_frame * frames


def read_packet_table(b, start, end, count):
    sizes = []
    pos = start
    while len(sizes) < count and pos < end:
        v = 0
        while pos < end:
            byte = b[pos]
            pos += 1
            v = (v << 7) | (byte & 0x7F)
            if not (byte & 0x80):
                break
        sizes.append(v)
    return sizes if len(sizes) == count else None


def convert(caf: bytes):
    if caf[:4] != b"caff":
        return None
    sample_rate = frames_per_packet = channels = priming = 0
    fmt = ""
    sizes = None
    data_start = data_len = -1
    pos = 8
    while pos + 12 <= len(caf):
        ctype = caf[pos:pos + 4].decode("ascii", "replace")
        size = struct.unpack_from(">q", caf, pos + 4)[0]
        body = pos + 12
        if ctype == "desc":
            sample_rate = int(struct.unpack_from(">d", caf, body)[0])
            fmt = caf[body + 8:body + 12].decode("ascii", "replace")
            frames_per_packet = struct.unpack_from(">I", caf, body + 20)[0]
            channels = struct.unpack_from(">I", caf, body + 24)[0]
        elif ctype == "pakt":
            num = struct.unpack_from(">q", caf, body)[0]
            priming = struct.unpack_from(">i", caf, body + 16)[0]
            end = len(caf) if size < 0 else min(body + size, len(caf))
            sizes = read_packet_table(caf, body + 24, end, num)
        elif ctype == "data":
            data_start = body + 4
            data_len = (len(caf) - data_start) if size < 0 else (size - 4)
        if size < 0:
            break
        pos = body + size

    if sizes is None or data_start < 0 or fmt.strip() != "opus":
        return None

    pre_skip = max(0, min(0xFFFF, priming))  # Opus counts are already 48 kHz

    head = b"OpusHead" + bytes([1, channels]) + struct.pack("<H", pre_skip) + \
        struct.pack("<I", 48000) + struct.pack("<H", 0) + bytes([0])
    vendor = b"smarttxt"
    tags = b"OpusTags" + struct.pack("<I", len(vendor)) + vendor + struct.pack("<I", 0)

    serial = 0x53545854
    out = bytearray()
    out += ogg_page(serial, 0, 0x02, 0, head, [len(head)])
    out += ogg_page(serial, 1, 0x00, 0, tags, [len(tags)])

    idx = 0
    cursor = data_start
    end = min(data_start + data_len, len(caf))
    completed = 0
    seq = 2
    while idx < len(sizes):
        page_body = bytearray()
        laces = []
        segc = 0
        while idx < len(sizes):
            sz = sizes[idx]
            segs = sz // 255 + 1
            if segc + segs > 255:
                break
            if sz <= 0 or cursor + sz > end:
                idx = len(sizes)
                break
            page_body += caf[cursor:cursor + sz]
            laces.append(sz)
            segc += segs
            completed += opus_packet_samples_48k(caf, cursor, sz)
            cursor += sz
            idx += 1
        if not laces:
            break
        last = idx >= len(sizes)
        out += ogg_page(serial, seq, 0x04 if last else 0x00, completed, bytes(page_body), laces)
        seq += 1
    return bytes(out)


if __name__ == "__main__":
    caf = open(sys.argv[1], "rb").read()
    ogg = convert(caf)
    if ogg is None:
        sys.stderr.write("not a CAF/Opus file\n")
        sys.exit(2)
    open(sys.argv[2], "wb").write(ogg)
    print(f"wrote {len(ogg)} bytes -> {sys.argv[2]}")
