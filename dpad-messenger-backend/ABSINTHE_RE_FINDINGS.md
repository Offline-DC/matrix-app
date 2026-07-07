# Absinthe / nac — reverse-engineering findings

Status: **working, verified end-to-end.** This documents how Apple's SmartTxt
validation-data ("nac" / "absinthe") generation works, and how it's implemented
here. The implementation was run against the real binary and a real Mac identity
and produced valid validation data (see §7).

Companion code:
- `nacserver/` — runnable Python relay (the verified reference).
- `absinthe/` — Rust crate mirroring the same logic for a native build.
- `ABSINTHE_REVERSE_ENGINEERING.md` — the operator-facing "how to run it" doc.

---

## 1. The problem

IDS registration requires a **validation-data** blob proving the request comes
from genuine Apple hardware. It's produced by three routines that Apple ships,
heavily obfuscated (a custom bytecode VM), inside `IMDAppleServices`. There is
no public clean-room reimplementation of the *algorithm* because it's
virtualized. The tractable, proven method (pypush, OpenBubbles, Beeper) is to
**emulate the untouched Apple binary** and feed it a captured hardware identity —
you don't need to understand the obfuscated logic if you just run it.

## 2. The binary and the three functions

- **Binary:** `IMDAppleServices`, a fat Mach-O. We use the **x86_64 slice**
  (offset `0x32b000` in the pinned build). sha1
  `e1181ccad82e6629d52c6a006645ad87ee59bd13`.
- The three functions are **not exported symbols** — they're reached by fixed
  offset into the x64 slice, valid only for this build:

  | function | slice offset | prologue |
  |---|---|---|
  | `nac_init` | `0xB1DB0` | `55 48 89 e5` |
  | `nac_key_establishment` | `0xB1DD0` | `55 48 89 e5` |
  | `nac_sign` | `0xB1DF0` | `55 48 89 e5` |

  Reconstructed System V AMD64 signatures:
  ```c
  int nac_init(const void *cert, int cert_len,
               void **out_ctx, void **out_request, int *out_request_len);
  int nac_key_establishment(void *ctx, const void *session_info, int len);
  int nac_sign(void *ctx, void *unused, int unused_len,
               void **out_validation_data, int *out_len);
  ```
  Nonzero return = Apple error code (interpreted as signed int32).

## 3. The emulation harness

An x86-64 CPU emulator (Unicorn) runs the slice; there is no OS under it, so
every imported libSystem/IOKit/CoreFoundation call must be serviced by a hook.

- **Memory map:** binary at `0x0`; stack `0x300000`; bump-heap `0x400000`;
  a `0xC3`(ret) "stop" page at `0x900000`; a `0xC3`-filled **hook page** at
  `0xD00000`.
- **Import redirection:** we parse the classic `LC_DYLD_INFO(_ONLY)` bind + lazy
  bind opcode streams to find every imported-pointer slot (1415 fixups, 257
  unique symbols in this build), and overwrite each slot with a unique 1-byte
  address inside the hook page. A code hook over that page dispatches each call
  to a Python/Rust handler; the `0xC3` then returns to the caller.
- **Calling in:** push the stop-page address as the return address, set SysV arg
  registers, run until control returns to the stop page, read `RAX`.

## 4. The hooks (what makes the hardware spoofing work)

37 imports are serviced. Most are trivial (`malloc` = bump; `free`/`CFRelease` =
no-op; `arc4random` = real RNG, needed for nonces). The load-bearing ones read
hardware identity:

- **`IORegistryEntryCreateCFProperty(entry, key, …)`** — reads the CFString key
  name and returns our spoofed value from the identity table. CFString reads
  (serial, UUID) come back as CFString; NVRAM/MAC/obfuscated reads and the
  NUL-terminated `board-id`/`product-name` come back as CFData.
- **Ethernet MAC path:** `IOServiceGetMatchingServices("IOEthernetInterface")`
  → `IOIteratorNext` (yields exactly one service, once) →
  `IORegistryEntryGetParentEntry` → `IORegistryEntryCreateCFProperty("IOMACAddress")`.
- **Root disk UUID:** `DADiskCopyDescription` returns a dict whose
  `DADiskDescriptionVolumeUUIDKey` is the captured root-volume UUID.
- CoreFoundation objects are modeled as a table indexed by 1-based handles (0 is
  avoided since it means NULL). Data-symbol constants read through the
  `0xC3`-filled hook page surface as the sentinel `0xC3C3C3C3C3C3C3C3`, which the
  dict lookup special-cases.

The exact IOKit key → identity-field mapping (12 reads) is in
`hardware.rs::iokit_properties` / `hardware.py::HardwareConfig.iokit`.

## 5. The network handshake

Two Apple round-trips bracket `nac_key_establishment`:

1. `GET http://static.ess.apple.com/identity/validation/cert-1.0.plist`
   → plist `{cert: <DER>}`. Feed `cert` to `nac_init` → get a request blob.
2. `POST https://identity.ess.apple.com/WebObjects/TDIdentityService.woa/wa/initializeValidation`
   body plist `{"session-info-request": <request>}` → `{"session-info": <bytes>}`.
   Feed `session-info` to `nac_key_establishment`. (Apple's chain isn't in most
   trust stores; clients disable TLS verification for this host.)
3. `nac_sign` → the validation-data bytes.

Downstream, rustpush puts these bytes (base64) in the IDS `register` request's
`validation-data` field, signed with the push + per-Apple-ID auth keys. Register
`status 6004` = "validation data expired" → regenerate; validation data is
short-lived, so it's produced fresh per registration/renewal.

## 6. The dumb file (OABS) — mapped to a real capture

The dumb file is a serialized `MacOSConfig`: 5-byte magic `OABS\0` + a protobuf
message. Recovered field layout (byte-exact round-trip verified), shown against
the real Mac17,2 / macOS 15.3.1 capture used for testing:

```
MacOSConfig
  #1 inner: HardwareConfig
       #1  product_name           "Mac17,2"
       #2  rom                    <6 bytes>            -> NVRAM ROM
       #3  platform_serial_number "HH9HTXD4C2"         -> IOPlatformSerialNumber
       #4  platform_uuid          <uuid>               -> IOPlatformUUID
       #5  root_disk_uuid         <uuid>               -> DADiskDescription…UUID
       #6  board_id               "Mac-22000000"       -> board-id (+NUL, CFData)
       #7  os_build_num           "24D70"              -> (client-info headers)
       #8  gq3489ugfi             <17 bytes>  ┐
       #9  fyp98tpgj              <17 bytes>  │ obfuscated "derived SmartTxt
       #10 kbjfrfpoju             <17 bytes>  │ keys" (IOPower:/), returned
       #12 oycqazlotndm           <17 bytes>  │ verbatim as CFData
       #14 abkpld1ecmni           <17 bytes>  ┘
       #11 mac_address            <6 bytes>            -> IOMACAddress
       #13 mlb                    "J33HKR00VPU0000VLK" -> NVRAM MLB
  #2 version           "15.3.1"
  #3 protocol_version  1640
  #4 device_id         <uuid == platform_uuid>
  #5 icloud_ua         "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0"
  #6 aoskit_version    "com.apple.AOSKit/282 (com.apple.accountsd/113)"
```

## 7. Verification (this is not theoretical)

Running the clean-room `nacserver` against the pinned `IMDAppleServices` and the
captured identity:

```
nac_init:              338-byte request
initializeValidation:  698-byte session-info   (live Apple round-trip)
nac_key_establishment: ok
nac_sign:              389-byte validation-data
```

and over the relay HTTP contract:

```
GET  /health          -> 200 {"status":"ok"}
POST /validation-data  -> 200 {"validationDataB64": "...389 bytes..."}
```

The Rust core (`absinthe`) is unit-tested against the same real binary and dumb
file: OABS round-trips byte-for-byte, the fat/Mach-O parser finds the x64 slice
and the nac prologues, and the bind interpreter resolves the IOKit imports.

The Rust **emulator** (`--features emulate`) is verified too — built against
`unicorn-engine` and run via `examples/generate.rs`, it produces the same
**389‑byte** validation data as the Python reference from the identical binary +
identity. So both the Python relay and the native Rust crate are proven, not
just structurally mirrored.

## 8. Ongoing obligations & risks

- **Build-pinned offsets.** New `IMDAppleServices` builds move the three
  offsets; re-derive them (find the `55 48 89 e5` prologues near the old
  offsets) and update the offset table.
- **Fingerprint invalidation.** Apple can flag a serial/board/MLB/ROM identity;
  rotate captures. Use throwaway Apple IDs.
- **Bundled FairPlay certs** (used for deviceless *activation*, a separate step
  in rustpush) are a distinct single point of failure from validation data.
- **Anisette** (`X-Apple-I-MD` / `-MD-M`) is required by the GrandSlam login and
  the IDS `register` transport, produced from ADI provisioning state — separate
  from validation data and handled on the rustpush side.

## Sources

Grounded in the public interoperability corpus: JJTech `pypush` (the emulated
nac approach; SSPL — **studied, not copied**), OpenBubbles `rustpush`
(`macos.rs`/`ids/user.rs`, the register flow + `MacOSConfig`), and the Apple
endpoints above. All code in `nacserver/` and `absinthe/` is a clean-room
reimplementation from the documented interfaces.
