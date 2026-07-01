# nacserver — open validation-data ("nac"/absinthe) relay

Generates iMessage **validation data** for the `:imessage` backend by running
Apple's obfuscated generator (`IMDAppleServices`) under CPU emulation, and
serves it over the HTTP contract the Android client already speaks
(`imessage/.../relay/HttpValidationDataRelay.kt`).

This is the runtime-verified reference implementation of the reverse
engineering. It was run end-to-end against a real binary and a real Mac17,2
identity and produced valid 389-byte validation data. The Rust `../absinthe`
crate mirrors this same logic for an on-device/native build. Full technical
writeup: [`../ABSINTHE_RE_FINDINGS.md`](../ABSINTHE_RE_FINDINGS.md).

## Why a relay (and not on-device)

The target is a low-RAM ARM flip phone. Apple's generator is an **x86-64**
binary; emulating it belongs off-device. So the fleet's phones POST their
hardware identity to this relay (plan §2.6 **option 2** — one shared Mac
identity serving many phones), and it returns the validation data. The phone's
`HttpValidationDataRelay` is already written to this contract.

## What you must supply (not in this repo)

1. **A dumb file** — a captured Mac hardware identity in OABS format (base64).
   This is the same artifact OpenBubbles captures from a real Mac. Keep it
   secret; it's your Mac's fingerprint.
2. **`IMDAppleServices`** — the Apple binary, extracted from a real Mac at
   `/System/Library/PrivateFrameworks/IMDAppleServices.framework/Frameworks/IDSFoundation.framework/IMDAppleServices`.
   The engine pins sha1 `e1181ccad82e6629d52c6a006645ad87ee59bd13` (the build the
   fixed function offsets were derived for); other builds need new offsets.

Both are `.gitignore`d. **Do not commit either.**

## Run

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r nacserver/requirements.txt        # unicorn + requests

python -m nacserver.server \
    --dumb ./dumb \
    --binary ./IMDAppleServices \
    --host 0.0.0.0 --port 8080 \
    --token "$RELAY_SHARED_SECRET"
```

Point the phone at it by setting, in the app:

```kotlin
IMessageConfig.validationRelayBaseUrl  = "https://relay.example.com"
IMessageConfig.validationRelayAuthToken = RELAY_SHARED_SECRET
```

## HTTP contract

```
GET  /health           -> 200 {"status":"ok"}
POST /validation-data   -> 200 {"validationDataB64":"<base64>"}
     Authorization: Bearer <token>            (if --token set)
     body: {"platformSerialNumber","mlb","rom","productName","osBuildNum",...}
```

Validation data is short-lived; the server caches it for `--cache-seconds`
(default 120s) so a burst of device polls doesn't re-emulate every time. The
phone re-requests on each registration/renewal.

## Modules

| file | role |
|---|---|
| `hardware.py` | OABS dumb-file parser + the IOKit property table |
| `macho.py` | fat/Mach-O loader + dyld bind-opcode interpreter |
| `emulator.py` | clean-room Unicorn harness (map binary, hook imports, call) |
| `nac.py` | the three nac calls, the import hooks, the Apple round-trips |
| `server.py` | the ValidationDataRelay HTTP server |

## Legal / safety

This is interoperability RE (same class as OpenBubbles/pypush/Beeper). It runs
Apple's *own* binary — no algorithm was reimplemented and nothing was extracted
from anyone's proprietary build. Registering with reverse-engineered validation
data can get Apple IDs / hardware identities flagged; use throwaway IDs and
expect to rotate. See `../ABSINTHE_REVERSE_ENGINEERING.md` §0.
