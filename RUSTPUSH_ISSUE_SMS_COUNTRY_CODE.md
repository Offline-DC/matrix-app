# `normalize_sms_handle` fabricates a `+` prefix, corrupting relayed SMS handles that arrive in national format

## Summary

`normalize_sms_handle` converts a bare all-digit SMS handle to E.164 by prefixing `+` and
nothing else. When Text Message Forwarding delivers a sender in **national** format —
which is what a NANP carrier does for a domestic text — a 10-digit number like
`2065550142` becomes `tel:+2065550142`. That is not the sender's number: `+20` is Egypt.

The correct, fully-qualified handle is already present in the same payload, in `fh`.

Every affected conversation splits in two, because outbound messages mirrored back from
the paired iPhone carry `h` **already** in E.164 and key correctly, while the other
party's inbound replies key to the fabricated address. A reply sent into the wrong half
is addressed to a number that does not exist and is silently dropped by the carrier —
there is no error anywhere in the stack.

## Where

`src/imessage/messages.rs`:

```rust
fn normalize_sms_handle(handle: &str) -> String {
    if handle.starts_with('+') {
        format!("tel:{handle}")
    } else if handle.len() >= 8 && handle.bytes().all(|b| b.is_ascii_digit()) {
        format!("tel:+{handle}")          // <-- fabricates a country code
    } else {
        format!("tel:{handle}") // short code or alphanumeric sender -- leave alone
    }
}
```

Called from the `RawSmsIncomingMessage` arm of `MessageInst::from_raw`
(`src/imessage/rawmessages.rs` → `messages.rs`) for `loaded.sender`, for every entry of
`loaded.participants` (`re`), and for `loaded.recieved_number`.

## Evidence

Field values taken from a real capture (numbers replaced; formats and the correlation are
verbatim). `h` is the field that gets normalized, `fh` the format string:

| `h` (used) | `fh` (correct, unused) | direction | result |
|---|---|---|---|
| `+12065550188` | `s:tel:+12065550142\|(null)(0)` | outbound echo | `tel:+12065550188` ✅ |
| `2065550188` | `s:tel:+12065550188\|(null)(0)` | inbound | `tel:+2065550188` ❌ |
| `2065550177` | `s:tel:+12065550177\|smil.xml(2)\|image.000000.jpg(6273)` | inbound MMS | `tel:+2065550177` ❌ |

The correlation across the capture was total: **every** inbound message arrived with `h`
in national format and was corrupted; every outbound echo arrived with `h` in E.164 and
was fine. Both SMS and MMS, across several carriers.

`fh` carried the fully-qualified handle in **100% of the corrupted cases**. This is not a
coincidence — `MessageParts::to_sms` builds that string as `format!("s:{}", from_handle)`,
so the first `|`-separated segment is always the qualified `tel:` handle.

## Impact

The obvious half is that inbound and outbound for the same person land in different chats.

The less obvious half is worse: for an MMS, `re` is non-empty and contains **the user's own
number**, also in national format. Any consumer that filters its own handles out of the
participant list to decide 1:1-vs-group will fail to match the corrupted copy, so the user
survives as a "counterpart", the participant count goes from 1 to 2, and a **1:1 MMS is
keyed as a group chat**. In our case an inbound photo landed in a phantom group room — it
downloaded fine, it was simply filed somewhere the user would never look, which read to
them as "MMS photos don't arrive". One contact ended up spread across three chats.

Downstream this also poisons any members→chat reverse index, since the member set for the
corrupted room can never match the real one.

## Suggested fix

Two independent improvements; the first alone fixes the reported symptom.

**1. Use `fh` for the sender.** It is authoritative, always present on a real relay
message, and needs no inference:

```rust
// `fh` is built as `format!("s:{}", from_handle)`, so segment 0 is the fully
// qualified handle. Prefer it over `h`, which the carrier may deliver in
// national format.
let normalized_sender = loaded.format
    .strip_prefix("s:")
    .and_then(|f| f.split('|').next())
    .filter(|h| !h.is_empty())
    .map(|h| h.to_string())
    .unwrap_or_else(|| normalize_sms_handle(&loaded.sender, &loaded.recieved_number));
```

**2. Give `normalize_sms_handle` the country it is normalizing into.**
`loaded.recieved_number` (`co`) is the user's own number and is always fully qualified, so
it can supply the country code for the remaining `re` entries:

```rust
fn normalize_sms_handle(handle: &str, own: &str) -> String {
    let h = handle.trim();
    if h.starts_with('+') { return format!("tel:{h}"); }
    if !(h.len() >= 8 && h.bytes().all(|b| b.is_ascii_digit())) {
        return format!("tel:{h}");                 // short code / alphanumeric
    }
    // NANP: `own` is "+1" + 10 digits, and a national number there is 10 digits.
    if own.starts_with("+1") && h.len() == 10 {
        return format!("tel:+1{h}");
    }
    format!("tel:+{h}")                            // unchanged for every other country
}
```

I've deliberately kept (2) to the NANP case. A blanket "prepend the caller's country code"
is tempting but wrong without a real libphonenumber: a legitimate 10-digit E.164 exists
(Norway `+47XXXXXXXX`, Denmark `+45XXXXXXXX`), so you cannot distinguish a dropped country
code from a valid short-country-code number by length alone. Restricting to NANP, where
the national number length is fixed at 10 and a `+`-plus-10-digits string is never valid,
keeps it unambiguous. Handling more countries properly probably wants `phonenumber` as an
optional dependency rather than more special cases.

Happy to open a PR with either or both if the approach looks right.

## Notes

- Reproduces on any inbound SMS whose carrier delivers the sender in national format; a
  local-number text between two domestic handsets did it first try for us.
- `RawSmsOutgoingMessage` takes a different path (`format!("tel:{}", p.phone_number)`,
  no fabricated `+`), which is why the two directions disagree.
- Short codes are unaffected here — they fall to the `else` branch — though consumers that
  canonicalize with a "bare digits get a country code" rule may add a `+` of their own
  further down.
