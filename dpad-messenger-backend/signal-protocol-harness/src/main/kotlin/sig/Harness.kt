package sig

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.signal.API_JSON
import com.offline.dpadmessenger.backend.signal.AccountAttributes
import com.offline.dpadmessenger.backend.signal.AndroidSignalProtocolStore
import com.offline.dpadmessenger.backend.signal.Capabilities
import com.offline.dpadmessenger.backend.signal.ConfirmDeviceRequest
import com.offline.dpadmessenger.backend.signal.DeviceMismatch
import com.offline.dpadmessenger.backend.signal.OldAndroidSignalProtocolStore
import com.offline.dpadmessenger.backend.signal.RetryReceiptLimiter
import com.offline.dpadmessenger.backend.signal.SignalAccount
import com.offline.dpadmessenger.backend.signal.SignalKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.signal.libsignal.metadata.InvalidMetadataMessageException
import org.signal.libsignal.metadata.ProtocolException
import org.signal.libsignal.metadata.ProtocolUntrustedIdentityException
import org.signal.libsignal.metadata.SealedSessionCipher
import org.signal.libsignal.metadata.certificate.CertificateValidator
import org.signal.libsignal.metadata.certificate.SenderCertificate
import org.signal.libsignal.metadata.certificate.ServerCertificate
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.DecryptionErrorMessage
import org.signal.libsignal.protocol.message.PlaintextContent
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.util.Base64
import java.util.Optional
import java.util.UUID

// ---------------------------------------------------------------- plumbing

var passed = 0
fun check(cond: Boolean, what: String) {
    if (!cond) { println("  FAIL: $what"); throw AssertionError(what) }
    passed++; println("  ok: $what")
}
inline fun <reified T : Throwable> expectThrows(what: String, block: () -> Unit): T {
    try { block() } catch (t: Throwable) {
        if (t is T) { check(true, what); return t }
        println("  FAIL: $what — got ${t.javaClass.name}: ${t.message}"); throw t
    }
    println("  FAIL: $what — nothing thrown"); throw AssertionError(what)
}
fun section(s: String) = println("\n== $s")

val trustRoot: ECKeyPair = ECKeyPair.generate()
val otherRoot: ECKeyPair = ECKeyPair.generate()   // a rotated-out root the cert is NOT signed by
val serverKey: ECKeyPair = ECKeyPair.generate()
val serverCert = ServerCertificate(trustRoot.privateKey, 1, serverKey.publicKey)
// Production order on the device is a list of validators; put the wrong root
// first so the loop has to fall through on a cert error, like rotation.
val validators = listOf(CertificateValidator(otherRoot.publicKey), CertificateValidator(trustRoot.publicKey))
const val NOW = 1_800_000_000_000L

fun cert(uuid: String, device: Int, id: IdentityKeyPair): SenderCertificate =
    serverCert.issue(serverKey.privateKey, uuid, Optional.empty(), device, id.publicKey.publicKey, NOW + 86_400_000)

/** Our flip: a linked device (deviceId 2) of account A, using the store under test. */
class Flip(val store: SignalProtocolStore, val aci: String, val identity: IdentityKeyPair) {
    val address = SignalProtocolAddress(aci, 2)
    private var nextOneTime = 100
    fun bundle(withOneTime: Boolean = true): PreKeyBundle {
        val spk = SignalKeys.generateSignedPreKey(identity, 1)
        val kpk = SignalKeys.generateKyberPreKey(identity, 2)
        store.storeSignedPreKey(1, spk.record)
        store.storeKyberPreKey(2, kpk.record)
        val ot = if (withOneTime) org.signal.libsignal.protocol.state.PreKeyRecord(nextOneTime++, ECKeyPair.generate()) else null
        ot?.let { store.storePreKey(it.id, it) }
        return PreKeyBundle(
            store.localRegistrationId, 2,
            ot?.id ?: PreKeyBundle.NULL_PRE_KEY_ID, ot?.keyPair?.publicKey,
            1, spk.record.keyPair.publicKey, spk.record.signature,
            identity.publicKey, 2, kpk.record.keyPair.publicKey, kpk.record.signature,
        )
    }
    /** The receive loop from SignalChatWebSocket.decryptSealedSender: next root only on a cert error. */
    fun receiveSealed(ct: ByteArray): Result<ByteArray> {
        val cipher = SealedSessionCipher(store, UUID.fromString(aci), null, 2)
        var lastCert: Throwable? = null
        for (v in validators) {
            try { return Result.success(cipher.decrypt(v, ct, NOW).paddedMessage) }
            catch (e: InvalidMetadataMessageException) { lastCert = e }
            catch (e: Throwable) { return Result.failure(e) }
        }
        return Result.failure(lastCert!!)
    }
}

/** A contact's phone (device 1) with a plain libsignal in-memory store. */
class Phone(val aci: String) {
    val identity: IdentityKeyPair = IdentityKeyPair.generate()
    val regId = (1..16380).random()
    val store = InMemorySignalProtocolStore(identity, regId)
    val address = SignalProtocolAddress(aci, 1)
    fun startSession(flip: Flip, withOneTime: Boolean = true) =
        SessionBuilder(store, flip.address).process(flip.bundle(withOneTime))
    fun sealedTo(flip: Flip, text: String): ByteArray =
        SealedSessionCipher(store, UUID.fromString(aci), null, 1)
            .encrypt(flip.address, cert(aci, 1, identity), text.toByteArray())
    fun bundleForFlip(): PreKeyBundle {
        val spk = SignalKeys.generateSignedPreKey(identity, 11)
        val kpk = SignalKeys.generateKyberPreKey(identity, 12)
        store.storeSignedPreKey(11, spk.record); store.storeKyberPreKey(12, kpk.record)
        return PreKeyBundle(regId, 1, PreKeyBundle.NULL_PRE_KEY_ID, null, 11, spk.record.keyPair.publicKey,
            spk.record.signature, identity.publicKey, 12, kpk.record.keyPair.publicKey, kpk.record.signature)
    }
}

class Device : Context()
fun account(aci: String, id: IdentityKeyPair) = SignalAccount(
    aci = aci, pni = null, phoneNumber = "", deviceId = 2, password = "pw",
    identityKeyPairBase64 = Base64.getEncoder().encodeToString(id.serialize()),
    profileKeyBase64 = "", registrationId = 4242,
)

fun newFlip(old: Boolean): Flip {
    val aci = UUID.randomUUID().toString()
    val id = IdentityKeyPair.generate()
    val acct = account(aci, id)
    val store: SignalProtocolStore =
        if (old) OldAndroidSignalProtocolStore(Device(), acct) else AndroidSignalProtocolStore(Device(), acct)
    return Flip(store, aci, id)
}

// ---------------------------------------------------------------- scenarios

/** Contact B talks to the flip, then reinstalls Signal (new identity + registration id) and keeps talking. */
fun reinstallScenario(old: Boolean): Pair<Flip, Phone> {
    val flip = newFlip(old)
    val bAci = UUID.randomUUID().toString()
    val b1 = Phone(bAci)
    b1.startSession(flip)
    check(flip.receiveSealed(b1.sealedTo(flip, "hi from B")).isSuccess, "first message from B decrypts")
    val b2 = Phone(bAci)                      // same account, new install
    b2.startSession(flip, withOneTime = false)
    return flip to b2
}

fun main() {
    section("Bug 1 — reproduce with the ORIGINAL store (beta d21fa78)")
    run {
        val (flip, b2) = reinstallScenario(old = true)
        val r = flip.receiveSealed(b2.sealedTo(flip, "after reinstall"))
        val e = r.exceptionOrNull()
        check(e is ProtocolUntrustedIdentityException, "message after reinstall fails with ProtocolUntrustedIdentityException (matches field log)")
        e as ProtocolException
        check(e.sender == b2.aci && e.senderDevice == 1, "exception carries sender + device (used by 1b logging)")
        val usmc = e.unidentifiedSenderMessageContent.orElse(null)
        check(usmc != null && usmc.type == CiphertextMessage.PREKEY_TYPE, "inner message is a PreKeySignalMessage (matches field log)")
        check(flip.receiveSealed(b2.sealedTo(flip, "again")).exceptionOrNull() is ProtocolUntrustedIdentityException,
            "every later message from B fails the same way (stored key never updates)")
    }

    section("Bug 1 — fixed store: same scenario")
    lateinit var fixed: Pair<Flip, Phone>
    run {
        Log.lines.clear()
        fixed = reinstallScenario(old = false)
        val (flip, b2) = fixed
        val r = flip.receiveSealed(b2.sealedTo(flip, "after reinstall"))
        check(r.isSuccess && String(r.getOrThrow()) == "after reinstall", "message after reinstall decrypts")
        check(Log.lines.count { it.contains("identity changed for") && it.contains("RECEIVING") } == 1,
            "logs 'identity changed … trusting new key' once")
        check(Log.lines.none { it.contains(b2.aci) }, "log line has no full ACI (prefix only)")
        val stored = flip.store.getIdentity(b2.address)
        check(stored == b2.identity.publicKey, "stored identity updated to the new key (saveIdentity ran)")
        repeat(3) { i ->
            check(flip.receiveSealed(b2.sealedTo(flip, "m$i")).isSuccess, "follow-up message $i decrypts")
        }
        check(Log.lines.count { it.contains("identity changed for") } == 1, "no further identity-change logs")
    }

    section("Bug 1 — reply from flip to B (SENDING direction) after B reinstalled")
    run {
        // Flip has a live session with B's OLD install (B never messaged after reinstalling).
        for (old in listOf(true, false)) {
            val flip = newFlip(old)
            val bAci = UUID.randomUUID().toString()
            val b1 = Phone(bAci); b1.startSession(flip)
            flip.receiveSealed(b1.sealedTo(flip, "hi"))
            val b2 = Phone(bAci)
            val sc = SessionCipher(flip.store, b1.address)
            check(sc.remoteRegistrationId == b1.regId && b1.regId != b2.regId,
                "[${if (old) "old" else "new"}] session reg id is B's OLD one → server answers 410 for device 1")
            if (old) {
                // Old code: stamped the bundle's reg id (no 410) and kept the stale session → B can't decrypt.
                // Even rebuilding fails: SessionBuilder checks isTrustedIdentity(SENDING).
                flip.store.deleteSession(b1.address)
                expectThrows<UntrustedIdentityException>("[old] building a session with B's new key throws UntrustedIdentity (SENDING)") {
                    SessionBuilder(flip.store, b1.address).process(b2.bundleForFlip())
                }
            } else {
                val store = flip.store as AndroidSignalProtocolStore
                check(store.containsSession(b1.address), "[new] containsSession true before archive")
                store.archiveSession(b1.address)  // what sendToRecipientDevices does on 410 staleDevices=[1]
                check(!store.containsSession(b1.address), "[new] containsSession false after archive → rebuild")
                SessionBuilder(flip.store, b1.address).process(b2.bundleForFlip())
                check(SessionCipher(flip.store, b1.address).remoteRegistrationId == b2.regId, "[new] rebuilt session carries B's NEW reg id → server accepts")
                val ct = SealedSessionCipher(flip.store, UUID.fromString(flip.aci), null, 2)
                    .encrypt(b2.address, cert(flip.aci, 2, flip.identity), "reply".toByteArray())
                val got = SealedSessionCipher(b2.store, UUID.fromString(bAci), null, 1)
                    .decrypt(CertificateValidator(trustRoot.publicKey), ct, NOW)
                check(String(got.paddedMessage) == "reply", "[new] B's new install decrypts the reply")
            }
        }
    }

    section("Archive keeps late messages decryptable (Signal semantics)")
    run {
        val flip = newFlip(false); val store = flip.store as AndroidSignalProtocolStore
        val b = Phone(UUID.randomUUID().toString()); b.startSession(flip)
        check(flip.receiveSealed(b.sealedTo(flip, "1")).isSuccess, "establish")
        val late = b.sealedTo(flip, "late")   // in flight on the current session
        store.archiveSession(b.address)
        check(flip.receiveSealed(late).isSuccess, "message on the archived session still decrypts")
    }

    section("containsSession for a session we only RECEIVED on (no send yet)")
    run {
        val flip = newFlip(false); val store = flip.store as AndroidSignalProtocolStore
        val c = Phone(UUID.randomUUID().toString()); c.startSession(flip)
        flip.receiveSealed(c.sealedTo(flip, "hello"))
        check(store.containsSession(c.address), "receiver-side session counts as sendable (no needless rebuild per send)")
        check(!store.containsSession(SignalProtocolAddress(c.aci, 7)), "unknown device → false")
    }

    section("1c — retry receipt round trip (what B's phone does with it)")
    run {
        // Use the OLD store to get a real failure, then check the receipt is one Signal can act on.
        val (flip, b2) = reinstallScenario(old = true)
        val ts = 1_799_999_999_123L
        val e = flip.receiveSealed(b2.sealedTo(flip, "lost?")).exceptionOrNull() as ProtocolException
        val usmc = e.unidentifiedSenderMessageContent.get()
        val err = DecryptionErrorMessage.forOriginalMessage(usmc.content, usmc.type, ts, e.senderDevice)
        val wire = PlaintextContent(err).serialize()
        // B side (Signal-Android MessageContentProcessor.handleRetryReceipt):
        val pc = PlaintextContent(wire)
        val back = DecryptionErrorMessage.extractFromSerializedContent(pc.body)
        check(back.timestamp == ts && back.deviceId == 1, "B reads timestamp + target device from the receipt")
        val rk = back.ratchetKey
        check(rk.isPresent, "receipt carries the ratchet key")
        val bSession = b2.store.loadSession(SignalProtocolAddress(flip.aci, 2))
        check(bSession.currentRatchetKeyMatches(rk.get()), "ratchet key matches B's current session → Signal archives + resends")
        // Unsealed (non-sealed envelope) variant: Signal maps envelope type → ciphertext type.
        val whisper = DecryptionErrorMessage.forOriginalMessage(usmc.content, CiphertextMessage.PREKEY_TYPE, ts, 1)
        check(whisper.timestamp == ts, "builds from envelope.content + PREKEY_TYPE too")
    }

    section("Sealed-sender loop: wrong trust root falls through, inner errors are final")
    run {
        val (flip, b2) = fixed
        val ct = b2.sealedTo(flip, "x")
        val cipher = SealedSessionCipher(flip.store, UUID.fromString(flip.aci), null, 2)
        expectThrows<InvalidMetadataMessageException>("cert from another root → InvalidMetadataMessageException (try next root)") {
            cipher.decrypt(CertificateValidator(otherRoot.publicKey), ct, NOW)
        }
        check(flip.receiveSealed(ct).isSuccess, "loop then succeeds on the right root")
        val dup = flip.receiveSealed(ct).exceptionOrNull()
        check(dup is org.signal.libsignal.metadata.ProtocolDuplicateMessageException, "replay → ProtocolDuplicateMessageException (ignored, no retry receipt)")
    }

    section("409 / 410 parsing (Signal-Server MismatchedDevicesResponse / StaleDevicesResponse)")
    run {
        val m = DeviceMismatch.parse(409, """{"missingDevices":[3],"extraDevices":[2,5]}""")!!
        check(m.missing == setOf(3) && m.toArchive == setOf(2, 5), "409 → archive extra, refetch for missing")
        val s = DeviceMismatch.parse(410, """{"staleDevices":[1]}""")!!
        check(s.toArchive == setOf(1), "410 → archive stale")
        check(DeviceMismatch.parse(410, "garbage")!!.toArchive.isEmpty(), "unreadable 410 body still triggers a refetch")
        check(DeviceMismatch.parse(500, "{}") == null && DeviceMismatch.parse(200, "") == null, "other statuses → not a mismatch")
    }

    section("Retry-receipt limiter")
    run {
        var now = 0L
        val l = RetryReceiptLimiter(maxPerWindow = 3, windowMs = 1000, clock = { now })
        check((1..3).all { l.tryAcquire("b") }, "a burst of 3 failures all get receipts")
        check(!l.tryAcquire("b"), "4th within the window is refused")
        check(l.tryAcquire("c"), "other senders are independent")
        now = 2001
        check(l.tryAcquire("b"), "budget resets after a quiet window")
    }

    section("Bug 2 — link request JSON (real data classes + real SignalApi Json config)")
    run {
        val k = SignalKeys.generateSignedPreKey(IdentityKeyPair.generate(), 1).json
        val numberless = API_JSON.encodeToString(ConfirmDeviceRequest.serializer(), ConfirmDeviceRequest(
            verificationCode = "code",
            accountAttributes = AccountAttributes(registrationId = 1, pniRegistrationId = null, name = null,
                capabilities = Capabilities(optionalPhoneNumber = true)),
            aciSignedPreKey = k, aciPqLastResortPreKey = k,
        ))
        println("    numberless: $numberless")
        val j = Json.parseToJsonElement(numberless).jsonObject
        check("pniSignedPreKey" !in j && "pniPqLastResortPreKey" !in j, "no PNI prekeys sent (server would 422)")
        val attrs = j["accountAttributes"]!!.jsonObject
        check("pniRegistrationId" !in attrs, "no pniRegistrationId (as Signal-Desktop)")
        check(attrs["fetchesMessages"]!!.jsonPrimitive.boolean, "fetchesMessages still present")
        val caps = attrs["capabilities"]!!.jsonObject
        check(caps["optionalPhoneNumber"]!!.jsonPrimitive.boolean, "optionalPhoneNumber=true (server would 409 without it)")
        check(caps["spqr"]!!.jsonPrimitive.boolean && caps["usernameChangeSyncMessage"]!!.jsonPrimitive.boolean, "existing capabilities unchanged")

        val numbered = API_JSON.encodeToString(ConfirmDeviceRequest.serializer(), ConfirmDeviceRequest(
            verificationCode = "code",
            accountAttributes = AccountAttributes(registrationId = 1, pniRegistrationId = 2, name = null),
            aciSignedPreKey = k, pniSignedPreKey = k, aciPqLastResortPreKey = k, pniPqLastResortPreKey = k,
        ))
        val n = Json.parseToJsonElement(numbered).jsonObject
        check("pniSignedPreKey" in n && "pniPqLastResortPreKey" in n, "number account: PNI prekeys still sent (regression)")
        val na = n["accountAttributes"]!!.jsonObject
        check(na["pniRegistrationId"]!!.jsonPrimitive.content == "2", "number account: pniRegistrationId still sent")
        check(!na["capabilities"]!!.jsonObject["optionalPhoneNumber"]!!.jsonPrimitive.boolean,
            "number account: optionalPhoneNumber=false (dropped server-side)")
    }

    section("Bug 2 — why nopni2 must ship with optional-PNI")
    run {
        expectThrows<Throwable>("old registerDevice path: deriving a PNI identity from empty bytes throws") {
            SignalKeys.deriveIdentityKeyPair(ByteArray(0), ByteArray(0))
        }
    }

    section("Stored-account compatibility")
    run {
        val oldJson = """{"aci":"a","pni":"p","phoneNumber":"+15550001111","deviceId":2,"password":"x","identityKeyPairBase64":"","profileKeyBase64":"","registrationId":1,"pniRegistrationId":3,"pniIdentityKeyPairBase64":"k","accountEntropyPool":"e"}"""
        val a = Json { ignoreUnknownKeys = true }.decodeFromString(SignalAccount.serializer(), oldJson)
        check(a.phoneNumber == "+15550001111" && a.authCredentialSaltBase64 == "", "account saved by the current build still loads")
    }

    println("\nALL $passed CHECKS PASSED")
}
