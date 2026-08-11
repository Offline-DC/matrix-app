package com.offline.dpadmessenger.backend.smarttxt.transport

import com.offline.dpadmessenger.backend.smarttxt.MacOSConfig
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

/**
 * The protocol boundary for SmartTxt. Everything above this line (session,
 * repository, UI) is transport-agnostic; everything below is "how do we
 * actually talk to Apple."
 *
 * Three implementations share this contract (SMARTTXT_NATIVE_BACKEND_PLAN.md
 * §2.6 / §3):
 *  - [RelayWebSocketTransport] — the **real** client: a JSON-over-WebSocket
 *    connection to a relay server (the relay holds the closed-source absinthe
 *    + a live Apple connection, à la BlueBubbles/OpenBubbles-hosted). This is
 *    the §2.6-option-2 "full validation relay/server" taken all the way: the
 *    relay does the protocol, the phone is a thin authenticated client.
 *  - [MockRelayTransport] — an in-process simulator implementing the exact same
 *    contract, so the entire live pipeline (session → repository → UI →
 *    notifications → persistence → renewal) is testable today without a relay.
 *  - [NativeRustPushTransport] — the eventual on-device path: rustpush compiled
 *    to a `.so` doing the protocol in-process. Stubbed until Phase B.
 *
 * The wire DTOs below are service-shaped (chat GUIDs, handles, tapbacks) so the
 * mapping to the UI's MessageRepository models is mechanical and identical
 * across all three transports.
 */
interface SmartTxtTransport {

    /** Pushed events from the relay/Apple (new messages, status, tapbacks…). */
    val events: SharedFlow<TransportEvent>

    /** Open the connection (WebSocket connect / native APNs socket). Suspends
     *  until connected or throws. Safe to call again after a drop. */
    suspend fun connect()

    /** Register this identity with Apple via the relay: the relay obtains
     *  validation data (closed-source absinthe, server-side), runs IDS
     *  registration, and returns the handles we can send as. */
    suspend fun register(config: MacOSConfig, appleId: String): RegisterResult

    /**
     * Rich registration entry point. Native/relay paths that need interactive
     * auth use [RegisterRequest.password] and call
     * [RegisterRequest.twoFactorProvider] when Apple demands a code. Defaults to
     * the simple [register] so existing transports (mock, etc.) compile and work
     * unchanged.
     */
    suspend fun register(request: RegisterRequest): RegisterResult =
        register(request.config, request.appleId)

    /** Initial + on-demand conversation list. */
    suspend fun getChats(): List<RelayChat>

    /** Page of messages for a chat, newest first, optionally older than a ts. */
    suspend fun getMessages(chatGuid: String, limit: Int, beforeMs: Long?): List<RelayMessage>

    /** Send a text. [tempGuid] is echoed on the delivered message so the repo
     *  can replace the optimistic bubble. */
    suspend fun sendText(chatGuid: String, text: String, tempGuid: String, replyToGuid: String?): SendAck

    /** SmartTxt tapback (reaction). [emoji] is the rendered tapback; classic
     *  tapbacks map to fixed emoji, arbitrary emoji pass through (plan §5). */
    suspend fun sendTapback(chatGuid: String, targetGuid: String, emoji: String, remove: Boolean): Boolean

    /** SmartTxt edit (15-min window). Returns false if rejected/expired. */
    suspend fun editMessage(chatGuid: String, targetGuid: String, newText: String): Boolean

    /** SmartTxt unsend (2-min window). Returns false if rejected/expired. */
    suspend fun unsendMessage(chatGuid: String, targetGuid: String): Boolean

    /** Mark [chatGuid] read up to [lastReadGuid] — the guid of the newest message
     *  FROM THEM. Always syncs read state to the user's OWN other Apple devices so
     *  their notification clears. [sendReceipt] gates the peer-facing receipt: when
     *  false (default) only the user's own devices are told; when true the sender is
     *  told too ("Read"). */
    suspend fun markRead(chatGuid: String, lastReadGuid: String, sendReceipt: Boolean): Boolean

    /** Typing indicator. Best-effort, no ack. */
    suspend fun setTyping(chatGuid: String, typing: Boolean)

    /** Relay-side contact list (the relay can expose the Mac's contacts). */
    suspend fun listContacts(): List<RelayContact>

    /** Create/resolve a chat for the given handles. */
    suspend fun createChat(addresses: List<String>, title: String?): RelayChat?

    /** Send a media attachment; bytes are uploaded to MMCS by the relay. [caption]
     *  rides the same message as the media (one bubble); "" sends media alone. */
    suspend fun sendAttachment(
        chatGuid: String,
        tempGuid: String,
        bytes: ByteArray,
        mimeType: String,
        name: String,
        caption: String = "",
        /** Guid of the message this media replies to; "" for a normal send. */
        replyTo: String = "",
    ): SendAck

    /** Download attachment bytes by guid (the relay handles MMCS download +
     *  decrypt). */
    suspend fun downloadAttachment(attachmentGuid: String): ByteArray?

    /** Re-establish auth without a full re-register (refresh tokens). Returns
     *  false if credentials are dead and a full re-register is required. */
    suspend fun reauth(): Boolean

    fun isConnected(): Boolean

    /** Tell the transport which message guids we already hold locally, so a server
     *  replay of history we've already stored is dropped instead of re-delivered.
     *  Call BEFORE [connect]. Only the native transport replays; the relay
     *  transports no-op. */
    fun seedSeen(guids: Collection<String>) {}

    /** Tell the transport which service each GROUP conversation runs on — true for
     *  MMS/SMS, false for iMessage — learned from the history we already hold. Call
     *  BEFORE [connect]. Only the native transport routes on it; the relay transports
     *  no-op. */
    fun seedGroupServices(services: Map<String, Boolean>) {}

    /** Tear down the connection and any background work. */
    fun shutdown()
}

// ---- pushed events ---------------------------------------------------------

sealed class TransportEvent {
    data object Connected : TransportEvent()
    data object Disconnected : TransportEvent()

    /** Conversation metadata appeared/changed. */
    data class ChatsUpdated(val chats: List<RelayChat>) : TransportEvent()

    /** New or updated messages (incoming, or our own echoed back). */
    data class MessagesUpdated(val messages: List<RelayMessage>) : TransportEvent()

    /** Delivery/read state changed for a message. [tempGuid] lets the repo
     *  match an optimistic outgoing bubble before the real guid is known. */
    data class MessageStatusChanged(
        val chatGuid: String,
        val guid: String,
        val tempGuid: String?,
        val status: String,
        /** "SMS" (green) or "iMessage" (blue); lets an optimistic bubble adopt its
         *  real color once the native side reports how it actually sent. */
        val service: String = "iMessage",
        /** Human-readable reason, set only on [status] == "failed" (an IDS 120 saying a
         *  recipient couldn't decrypt). Surfaced as the message's `errorReason` so a
         *  failed bubble explains itself instead of just turning red. */
        val detail: String = "",
    ) : TransportEvent()

    /** A tapback was added/removed on a message. */
    data class TapbackUpdated(
        val chatGuid: String,
        val targetGuid: String,
        val emoji: String,
        val senderAddress: String,
        val isFromMe: Boolean,
        val remove: Boolean,
        /** The reaction's real send time (ms). 0 if unknown. Used to bump the chat
         *  list by when the reaction happened, not when it was received. */
        val timestampMs: Long = 0L,
        /** The reaction message's OWN guid (Apple's message id for this tapback). Lets a
         *  cross-device "read up to this reaction" resolve the room, since reactions are
         *  folded into their target and never stored as their own message. Blank if
         *  unknown (older FFI / relay that doesn't send it). */
        val guid: String = "",
    ) : TransportEvent()

    /** A whole poll cycle's worth of status changes / tapbacks, so a bulk
     *  catch-up applies them with ONE state update instead of one per item
     *  (avoids N recompositions during a burst on low-RAM devices). */
    data class MessageStatusBatch(val items: List<MessageStatusChanged>) : TransportEvent()
    data class TapbackBatch(val items: List<TapbackUpdated>) : TransportEvent()

    /**
     * The transport is (or is no longer) working through a backlog burst — the
     * replay Apple sends after the phone has been off, arriving as many bounded
     * batches rather than one huge one.
     *
     * The repository uses this to coalesce its expensive per-batch work: a full
     * cache re-serialize + Keystore-encrypted write costs the same whether one
     * message changed or a thousand, so doing it on the normal 1.5s debounce for
     * the whole length of a catch-up is pure waste on a low-RAM device — and it is
     * exactly the waste that chunked delivery would otherwise introduce. Suppress
     * while [active] is true, then do it once when it goes false.
     *
     * Advisory, never load-bearing: a transport that never emits it (relay, mock)
     * simply behaves as it always did, and a catch-up that is cut short by the
     * process dying is re-delivered by the next connect's replay anyway.
     */
    data class CatchUpChanged(val active: Boolean) : TransportEvent()

    /** Typing indicator toggled in a chat. */
    data class TypingChanged(val chatGuid: String, val typing: Boolean) : TransportEvent()

    /** A chat was read on another of my devices — clear its unread + notification
     *  here (the read didn't happen on THIS device, so nothing else clears it).
     *  [chatGuid] may be blank when Apple's self-synced read carries no counterpart;
     *  [messageGuid] (the guid of the message read up to) then identifies the room. */
    data class ChatRead(val chatGuid: String, val messageGuid: String = "") : TransportEvent()

    /** Credentials died and could not be refreshed — re-register needed. */
    /** rustpush's IDS registration state changed. [needsRelogin] marks the one
     *  case rustpush deliberately refuses to retry — an IDS 6005 — which needs a
     *  real sign-in, not a reconnect. Everything else it retries itself with
     *  backoff, so those arrive here for logging only. */
    data class RegistrationFailed(val needsRelogin: Boolean, val error: String) : TransportEvent()

    /** Apple is refusing this device's APS connect, and has been for long enough that
     *  the push certificate is dead. [rejected] false withdraws it after a connect
     *  succeeds.
     *
     *  Read from rustpush's own `resource_state` watch channel — the same public signal
     *  OpenBubbles uses to show this, which is why it needs no rustpush patch.
     *
     *  Distinct from [RegistrationFailed] on purpose: IDS registration can be perfectly
     *  valid while the push certificate behind it is refused, which is exactly the state
     *  that reads as "my texts send but nobody ever replies". */
    data class PushCertRejected(val rejected: Boolean) : TransportEvent()

    data object AuthExpired : TransportEvent()
}

// ---- wire DTOs (service-shaped) --------------------------------------------

@Serializable
data class RelayChat(
    val guid: String,
    val displayName: String = "",
    val isGroup: Boolean = false,
    val participants: List<RelayParticipant> = emptyList(),
    val unread: Boolean = false,
)

@Serializable
data class RelayParticipant(
    /** Apple handle, e.g. "mailto:me@icloud.com" or "tel:+15551234567". */
    val address: String,
    val displayName: String = "",
    val avatarColor: String = "",
    val isMe: Boolean = false,
)

/**
 * A message on the wire. Field set mirrors BlueBubbles' message schema so a
 * BlueBubbles-compatible relay maps in directly:
 *  - delivery is carried as [dateDelivered]/[dateRead] (BlueBubbles style); the
 *    repository derives [MessageStatus] from them (read > delivered > sent);
 *  - tapbacks arrive AS messages with [associatedMessageType] (BlueBubbles
 *    `associatedMessageType`, see [Tapback]) + [associatedMessageGuid] pointing
 *    at the target, rather than as a separate array — the repository folds
 *    those into the target's reactions instead of showing a bubble;
 *  - [expressiveSendStyleId] carries message effects (slam, invisible ink…).
 */
@Serializable
data class RelayMessage(
    val guid: String,
    val chatGuid: String,
    val tempGuid: String = "",
    val senderAddress: String = "",
    val isFromMe: Boolean = false,
    val text: String = "",
    val subject: String = "",
    val timestampMs: Long = 0L,
    /** 0 = not delivered yet. */
    val dateDelivered: Long = 0L,
    /** 0 = not read yet. */
    val dateRead: Long = 0L,
    val replyToGuid: String = "",
    /** "iMessage" (blue) or "SMS" (green). */
    val service: String = "iMessage",
    /** Explicit status if the relay sends one; otherwise derived from
     *  dateDelivered/dateRead. "sending|sent|delivered|read|failed". */
    val status: String = "",
    /** BlueBubbles tapback association: non-zero type means this message IS a
     *  tapback on [associatedMessageGuid]. See [Tapback]. */
    val associatedMessageType: Int = 0,
    val associatedMessageGuid: String = "",
    /** Message effect bundle id, e.g. "com.apple.MobileSMS.expressivesend.impact". */
    val expressiveSendStyleId: String = "",
    /** Inline (per-message) reactions, when the relay pre-aggregates them. */
    val reactions: List<RelayReaction> = emptyList(),
    val attachments: List<RelayAttachment> = emptyList(),
    val editedAtMs: Long = 0L,
    val isUnsent: Boolean = false,
    /** Group conversation display name (iMessage cv_name); blank for 1:1. */
    val chatName: String = "",
    /** Group members (OTHER-party addresses) for this message's conversation. Sent by
     *  the native side because a group guid is now the opaque Apple gid, so members can
     *  no longer be parsed out of it. Empty for a 1:1. */
    val participants: List<String> = emptyList(),
)

@Serializable
data class RelayReaction(
    val emoji: String,
    val senderAddress: String = "",
    val isFromMe: Boolean = false,
)

@Serializable
data class RelayAttachment(
    val guid: String,
    val mimeType: String = "",
    val name: String = "",
    /** "image" | "video" | "other". */
    val kind: String = "other",
)

@Serializable
data class RelayContact(
    val name: String,
    val address: String,
    val avatarColor: String = "",
)

/**
 * Rich registration request. Native/relay paths that need interactive auth
 * use [password] and call [twoFactorProvider] when Apple demands a code.
 *
 * The password is transient — it is passed straight into the register call and
 * never persisted (see [com.offline.dpadmessenger.backend.smarttxt
 * .SmartTxtRepository]).
 */
class RegisterRequest(
    val config: MacOSConfig,
    val appleId: String,
    val password: String = "",
    val twoFactorProvider: (suspend () -> String?)? = null,
    /** Invoked when Apple demands an FSA (security-key) challenge instead of a
     *  code. Receives the challenge so the UI can show its screen and relay it to
     *  the companion, and suspends until the companion's assertion comes back (or
     *  null if the user cancels). */
    val fsaProvider: (
        suspend (com.offline.dpadmessenger.backend.smarttxt.FsaChallenge)
        -> com.offline.dpadmessenger.backend.smarttxt.FsaResponse?
    )? = null,
)

/** Result of [SmartTxtTransport.register]. */
sealed class RegisterResult {
    data class Success(val handles: List<String>) : RegisterResult()
    data class Failure(val message: String) : RegisterResult()
}

/** Ack for an outgoing send. [guid] is the server-assigned id once known. */
data class SendAck(val ok: Boolean, val guid: String? = null, val error: String? = null)
