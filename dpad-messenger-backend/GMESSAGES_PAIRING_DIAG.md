# Pairing diagnostics — `finishErrorType=3 finishErrorCode=32`

Branch: `diag/gaia-pairing-attestation`. Instrumentation only; no behaviour change
with default config.

## What we know

`32` is **`CLIENT_ATTESTATION_MISSING`** — Google's own name, from
`mautrix/gmessages` `pkg/libgm/gmproto/authentication.proto` (read 2026-09-19).
The enum runs to 35 now; 32–35 post-date their v0.2608.0 release:

| code | name |
|---:|---|
| 32 | `CLIENT_ATTESTATION_MISSING` |
| 33 | `CLIENT_ATTESTATION_MISMATCH` |
| 34 | `SI_RESPONSE_FROM_UNEXPECTED_PHONE_REGISTRATION_ID` |
| 35 | `CLIENT_ATTESTATION_REVISION_MISMATCH` |

The same file shows `GaiaPairingRequestContainer` has a **field 8,
`privateAPIConfirmation`**, which this codebase has never written — and neither
does mautrix's client.

The refusal arrives **520–690 ms** after CLIENT_FINISHED. `sendPairingMessage`
waits `WAIT_FOREVER` there *because a human has to tap an emoji*. Sub-second
means the **server** refused us and the handset was never asked.

## What this branch adds

- `GaiaPairingErrorCode` names in the log — `errCode=32 (CLIENT_ATTESTATION_MISSING)`.
- Field census + bounded hex of **every** request and response container, including
  response fields **3, 4 and 8** which the parser previously dropped.
- Elapsed-time verdict on the failure line: under 3 s is flagged as a server-side
  refusal in the log itself.
- `/web/config` **cohort fingerprint** under the `GMGaiaPair` tag — web-build
  version, region, the two 32-hex ids, the **experiment-id array** and any
  non-binary feature flag.
- Device-list summary (`DEVICES n=… primaries=… destUuid=…`) mirrored under
  `GMGaiaPair`, because `GMGaia` is **not** in the launcher's rolling-logcat filter
  and no customer bundle has ever carried it.
- User-facing copy that stops accusing the user. A pre-tap refusal no longer says
  "Pairing was declined or the emoji didn't match — try again."

## Safe for support captures

The dumps cover the pairing **container** only. The tachyon auth token and the
cookie jar live in the RPC envelope one level up and are never logged. The config
fingerprint deliberately omits the API keys, the OAuth client id and the body.
**Do not widen either to the envelope** — these logs get emailed.

## Greps for the returned capture

```
PROTO CLIENT_FINISHED response (FAILED)
PROTO request
CONFIG experiments=
CONFIG oddFlags=
DEVICES n=
pairing failed: errType=
```

## Decision table

| Observation | Reading |
|---|---|
| `field8[N]=…` non-empty on the failure | Google is handing back a challenge. **Decode it first** — it may be the whole fix |
| `field8` empty, `errCode=32`, sub-second | Attestation is required and absent. Confirms the diagnosis; the value has to come from the web client |
| `CONFIG experiments=` differs from a working customer's | The rollout flag is in that diff. **This is the cheapest answer to "why him"** |
| Same experiments as a working customer | Not a cohort flag — look at account state, region, or the device list |
| `DEVICES primaries>1` or `destUuid` not the user's phone | We may be pairing at the wrong handset; `34` exists for exactly this |

## The optional probe

```kotlin
GoogleMessagesConfig.pairingPrivateApiConfirmation = "diag-probe"
```

Sends field 8 with an obviously synthetic value. If `32` becomes `33` or `35`,
that proves field 8 is the field being checked, that the server parses it, and
that our container is otherwise acceptable. If it stays `32`, the check is
looking somewhere else. **Leave it null for the first capture** — establish the
baseline before perturbing it.
