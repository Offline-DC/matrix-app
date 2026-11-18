# Signal protocol harness

Runs the messenger's real Signal protocol-store code against real libsignal
(JVM build of the same 0.86.5) to reproduce and verify the fixes in
`fix/signal-identity-and-nopni2`:

- **Bug 1**: a contact reinstalls Signal → every message from them fails with
  `ProtocolUntrustedIdentityException` (reproduced with the frozen pre-fix
  store), then decrypts with the fixed store; replying to them works
  (stale session → 410 → archive → rebuild with the new identity).
- Retry receipts: the `DecryptionErrorMessage` we build is one Signal's phone
  can act on (timestamp, device, ratchet key matches its session).
- **Bug 2**: the link request JSON for numberless vs numbered accounts.

```
cd dpad-messenger-backend/signal-protocol-harness
./gradlew -q run        # prints each check; ends with "ALL n CHECKS PASSED"
```

Not part of the app build (not in `../settings.gradle.kts`); safe to ignore in CI.
