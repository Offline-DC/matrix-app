# Fix: relayed SMS/MMS handles lose their country code when the carrier sends national format

`normalize_sms_handle` builds E.164 by prefixing `+` to any all-digit address. When Text
Message Forwarding delivers a sender in **national** format the result is a well-formed but
wrong number, and every chat with that person forks in two.

Mostly log below so you can judge it yourself. Phone numbers are replaced 1:1 with
555-555-01xx, message bodies and attachment bytes elided; field names, ordering and the
`h`/`fh`/`co`/`re` relationships are verbatim. The user's own number is `+15555550101`.

## What arrives

```
08-26 16:38:28.482  1889  2251 D SmartTxtRust: rustpush::imessage::messages: xml: Dictionary({"re": Array([]), "h": String("5555550102"), "fco": Integer(1), "co": String("+15555550101"), "w": Date(2026-08-26T21:38:28.631626009Z), "fh": String("s:tel:+15555550102|(null)(0)"), "cs": String("07CB3C57-61A7-4691-881E-E5581A29A6E9"), "r": Boolean(true), "k": Array([Dictionary({"type": String("text/plain"), "data": Data([..])})]), "_ssc": Integer(0), "l": Integer(0), "sV": String("1"), "_sc": Integer(0), "m": String("sms"), "ic": Integer(1), "n": String("us"), "g": String("5DA211C3-69CF-CEC5-47A3-3AC200AFCA1F")})
```

`h` is the sender with no country code. `co` (the receiving number) is qualified, and so is
`fh`. `normalize_sms_handle` sees ten bare digits, prefixes `+`, and the sender becomes
`+5555550102` — a different number entirely:

```
08-26 16:38:28.485  1889  2251 I SmartTxtRust: smarttxt_ffi: recv msg: guid=5DA211C3-69CF-CEC5-47A3-3AC200AFCA1F ts=1787780308631 sender=tel:+5555550102 is_from_me=false is_group=false counterparts=["+5555550102"] cv_name=none chat_guid=iMessage;-;+5555550102
```

The user's own messages to this person come back from the paired iPhone with `h` already in
E.164, so they key to `+15555550102`. The two halves of the conversation never meet, and a
reply typed into the wrong half is addressed to a number that doesn't exist. Nothing
objects — IDS returns zero keys, which is also what a real Android recipient looks like:

```
08-26 16:38:48.343  1889 10974 D SmartTxtRust: rustpush::imessage::messages: sending: Ok(Dictionary({"re": Array([Dictionary({"id": String("+5555550102")})]), "ic": Integer(0), "chat-style": String("im"), "mD": Dictionary({"handle": String("+5555550102"), "service": String("SMS"), "sV": String("1"), "guid": String("451532CC-FD86-414D-AFBA-A153A9229273"), "plain-body": String("uh oh")})}))
```

## `re` carries the user's own number, so 1:1 threads become groups

```
08-27 10:01:03.060  1889  2250 D SmartTxtRust: rustpush::imessage::messages: xml: Dictionary({"re": Array([String("5555550106"), String("5555550109"), String("5555550101"), String("5555550104"), String("5555550102"), String("5555550108"), String("5555550107")]), "h": String("5555550103"), "fco": Integer(1), "co": String("+15555550101"), "w": Date(2026-08-27T15:01:02.84157598Z), "fh": String("s:tel:+15555550103|smil.xml(2)|text000001.txt(0)"), "b": String("application/vnd.wap.multipart.related"), "cs": String("07CB3C57-61A7-4691-881E-E5581A29A6E9"), "r": Boolean(true), "k": Array([Dictionary({"data": Data([..]), "content-id": String("<smil>"), "type": String("application/smil"), "content-location": String("smil.xml")}), Dictionary({"data": Data([..]), "content-id": String("<text000001>"), "type": String("text/plain"), "content-location": String("text000001.txt")})]), "_ssc": Integer(0), "l": Integer(0), "sV": String("1"), "_sc": Integer(0), "m": String("mms"), "ic": Integer(1), "n": String("us"), "g": String("49ED01B8-640B-4DC6-B972-26F699B90A7B")})
```

Seven `re` entries, all national, including `5555550101` — the user. A client that strips
its own handles to decide 1:1-vs-group can't match the damaged copy, so the user survives as
a counterpart:

```
08-27 10:01:03.072  1889  2250 I SmartTxtRust: smarttxt_ffi: recv msg: guid=49ED01B8-640B-4DC6-B972-26F699B90A7B ts=1787842862841 sender=tel:+5555550103 is_from_me=false is_group=true counterparts=["+5555550104", "+5555550102", "+5555550103", "+5555550106", "+5555550101", "+5555550107", "+5555550108", "+5555550109"] cv_name=none chat_guid=iMessage;+;+5555550104,+5555550102,+5555550103,+5555550106,+5555550101,+5555550107,+5555550108,+5555550109
```

Same effect on a two-party MMS, which is how this reached us — reported as "MMS photos
never arrive":

```
08-28 08:36:19.991  1889  2250 I SmartTxtRust: smarttxt_ffi: recv msg: guid=2AB2FB19-CE6F-45F0-8E7B-D1D8A2BEAE55 ts=1787924179000 sender=tel:+5555550102 is_from_me=false is_group=true counterparts=["+5555550102", "+5555550101"] cv_name=none chat_guid=iMessage;+;+5555550102,+5555550101
08-28 08:36:50.049  1889  1889 I IMsgRepo: downloadMedia: got 627389 bytes for msg=2AB2FB19-CE6F-45F0-8E7B-D1D8A2BEAE55
```

The attachment was fine. It landed in a conversation the user had no reason to open, and we
spent about a week looking at the attachment pipeline before anyone looked at the handle.

## Every relayed message in the capture

58 SMS/MMS relays over ~4 days, one device:

| `h` | `fh` first segment | n | mangled |
|---|---|---|---|
| `+1555555011x` (6 numbers) | `s:tel:+15555550101` (the user) | 36 | |
| `5555550102` | `s:tel:+15555550102` | 14 | yes |
| `5555550103` / `5555550104` / `5555550105` / `5555550110` | matching qualified | 4 | yes |
| `61607`, `30295` | short codes | 4 | |

The split is total: every message where `fh` names the *other* party arrived national, every
message where `fh` is the user's own number arrived qualified, no exceptions. One number
appears on both sides — qualified one direction, national the other — so it isn't per-contact.

## The change

`co` is the user's own number and is always fully qualified, so it can name the country:

```rust
fn normalize_sms_handle(handle: &str, own: &str) -> String {
    let h = handle.trim();
    if h.starts_with('+') { return format!("tel:{h}"); }
    if !(h.len() >= 8 && h.bytes().all(|b| b.is_ascii_digit())) {
        return format!("tel:{h}"); // short code or alphanumeric sender -- leave alone
    }
    if own.trim().starts_with("+1") && h.len() == 10 {
        return format!("tel:+1{h}");
    }
    format!("tel:+{h}")
}
```

The four call sites in the `RawSmsIncomingMessage` arm pass `&loaded.recieved_number`.

**NANP only, because it's the only case I can prove.** A NANP national number is exactly ten
digits and `+` plus ten digits is never valid NANP, so the inference is unambiguous. A
ten-digit E.164 is real elsewhere (Norway `+47XXXXXXXX`, Denmark `+45XXXXXXXX`) and I can't
separate that from a dropped country code by length. Going further probably means
`phonenumber` as an optional dep, which felt like your call. Every other input returns
exactly what it did before — I diffed 15 handle shapes against 5 account shapes, 69 of 75
identical, and the 6 that differ are all NANP-account-plus-bare-ten-digits.

**Why not read `fh`.** It was my first attempt. It looks authoritative on a plain SMS relay
(`to_sms` builds it as `format!("s:{}", from_handle)`, and in the first example it does
hold the qualified sender), but on an RCS relay it holds the *recipient's own* handle:

```
08-27 17:05:53.178  1889  2248 D SmartTxtRust: rustpush::imessage::messages: xml: Dictionary({"re": Array([String("+15555550114"), String("+15555550113"), String("+15555550111"), String("+15555550112"), String("+15555550116"), String("+15555550115")]), "h": String("+15555550111"), "fco": Integer(1), "co": String("+15555550101"), "w": Date(2026-08-27T22:05:53.344333052Z), "fh": String("s:tel:+15555550101|(null)(0)"), "cgi": String("35393536334341452D374337372D343330322D393245422D323131414136413531433239"), "ocgi": String("35393536334341452D374337372D343330322D393245422D323131414136413531433239"), "r": Boolean(true), "cs": String("07CB3C57-61A7-4691-881E-E5581A29A6E9"), "k": Array([Dictionary({"type": String("text/plain"), "data": Data([..])})]), "cdn": String(".."), "_ssc": Integer(0), "l": Integer(0), "service": String("RCS"), "sV": String("1"), "m": String("sms"), "ic": Integer(1), "g": String("9AA000CF-EA7F-4EA4-A769-48D2217ED6DD")})
```

`h` is the sender, `fh` is the user. Preferring `fh` turns a 1:1 RCS thread into a chat with
yourself. `co` looks dependable in a way `fh` isn't — it's the receiving number by
definition, and was qualified in all 58 messages.

Short codes are untouched either way; they fall to the length check.

## What I can't tell you

- **One device, one user, four days.** I don't know how common national-format `h` is or
  which carriers send it. If your captures show `h` always qualified, that's worth knowing
  and would mean this is narrower than I think.
- **That user's setup may be unusual.** She's the same person who reported the missing MMS
  photos, and she's had a bumpy time generally — I can't rule out something odd about her
  carrier or configuration contributing. Better said up front than presented as universal.
- `n` is documented as `// always 310 (missing)`; on this device it's `String("us")` in all
  19 messages that carry it, which made me think these fields vary more than assumed.

Worth checking your own logs:

```
grep -o '"h": String("[0-9]\{8,15\}")' *.log   # bare-digit h — country code inferred, not received
```

## Testing

No unit tests — didn't want to guess at your conventions for this file, happy to add them.
What I verified was the twelve real payloads above replayed through the patched function plus
the caller's chat-guid logic: the split 1:1 and the group MMS resolve to the same room as the
user's own outbound; RCS, short codes, already-qualified inbound and non-NANP accounts are
unchanged.

Happy to split this up, change the approach, or take only the plumbing if you'd rather handle
the country question differently.
