package com.offline.dpadmessenger.backend.gmessages

/**
 * Wire formats for the *authenticated* Google Messages session — everything
 * after pairing. Two layers:
 *
 *  - Outer envelopes are pblite (JSON-array protobuf): OutgoingRPCMessage,
 *    AckMessageRequest, RegisterRefreshRequest, and the streamed
 *    LongPollingPayload elements.
 *  - Inner payloads are binary protobuf: OutgoingRPCData / RPCMessageData,
 *    and inside those (AES-CTR+HMAC encrypted, see [GMCrypto.encryptPayload])
 *    the actual request/response messages: SendMessageRequest, UpdateEvents,
 *    Conversation, Message, …
 *
 * Field numbers transcribed from mautrix-gmessages `pkg/libgm/gmproto/`
 * (rpc.proto, client.proto, conversations.proto, events.proto,
 * authentication.proto). Only the fields we use are modelled — the readers
 * skip unknown fields, proto3 writers omit defaults.
 *
 * Everything here is pure JVM (no Android APIs) so it's unit-testable.
 */
internal object GMSessionProto {

    // --- ActionType (rpc.proto) -------------------------------------------
    const val ACTION_LIST_CONVERSATIONS = 1
    const val ACTION_LIST_MESSAGES = 2
    const val ACTION_SEND_MESSAGE = 3
    const val ACTION_LIST_CONTACTS = 6
    const val ACTION_GET_OR_CREATE_CONVERSATION = 9
    const val ACTION_MESSAGE_READ = 10
    const val ACTION_GET_UPDATES = 16
    const val ACTION_ACK_BROWSER_PRESENCE = 17
    const val ACTION_NOTIFY_DITTO_ACTIVITY = 22
    const val ACTION_SEND_REACTION = 38

    // SendReactionRequest.Action
    const val REACTION_ADD = 1
    const val REACTION_REMOVE = 2

    // BugleRoute (rpc.proto)
    const val ROUTE_DATA_EVENT = 19
    const val ROUTE_PAIR_EVENT = 14

    // MessageType (rpc.proto)
    const val MSGTYPE_BUGLE_MESSAGE = 2
    const val MSGTYPE_BUGLE_ANNOTATION = 16

    /** ConfigVersion as pblite — indices 2,3,4,6,8 = Year,Month,Day,V1,V2.
     *  MUST match GMPairingProto.configVersion() (binary form). */
    const val CONFIG_VERSION_PBLITE = "[null,null,2026,3,18,null,4,null,6]"

    // =======================================================================
    // Long-poll: incoming
    // =======================================================================

    /** One parsed element of the ReceiveMessages stream (LongPollingPayload). */
    sealed class LongPollEvent {
        /** field 2: IncomingRPCMessage */
        data class Data(val rpc: IncomingRpc) : LongPollEvent()
        /** field 4: StartAckMessage { count=1 } — events we saw before, re-delivered. */
        data class AckCount(val count: Int) : LongPollEvent()
        /** field 3 / field 5: keepalives. */
        data object Heartbeat : LongPollEvent()
    }

    /**
     * IncomingRPCMessage (subset):
     *   responseID=1, bugleRoute=2, messageData=12 (RPCMessageData or RPCPairData)
     */
    data class IncomingRpc(
        val responseId: String,
        val bugleRoute: Int,
        val messageData: ByteArray?,
    )

    /** Parse one streamed LongPollingPayload element (a pblite JSON array). */
    fun parseLongPollElement(element: String): LongPollEvent? {
        val node = PbLite.parse(element) as? PbLite.Node.Arr ?: return null
        // field 2 = data
        (node[1] as? PbLite.Node.Arr)?.let { data ->
            return LongPollEvent.Data(
                IncomingRpc(
                    responseId = data[0].asStringOrNull() ?: "",
                    bugleRoute = data[1].asIntOrNull() ?: 0,
                    messageData = data[11].asStringOrNull()?.let(B64::decode),
                )
            )
        }
        // field 4 = ack { count=1 }
        (node[3] as? PbLite.Node.Arr)?.let { ack ->
            return LongPollEvent.AckCount(ack[0].asIntOrNull() ?: 0)
        }
        // field 3 heartbeat / field 5 startRead
        if (node[2] is PbLite.Node.Arr || node[4] is PbLite.Node.Arr) return LongPollEvent.Heartbeat
        return null
    }

    /**
     * RPCMessageData (rpc.proto) — binary proto inside a DataEvent:
     *   sessionID=1, timestamp=3, action=4, unencryptedData=5,
     *   encryptedData=8, encryptedData2=11
     *
     * `sessionID` echoes the requestID of the RPC this is a response to (for
     * pushed events it's the phone's own id). `encryptedData` decrypts (with
     * the QR AES/HMAC keys) to the action-specific response proto.
     */
    data class RpcMessageData(
        val sessionId: String,
        val action: Int,
        val unencryptedData: ByteArray?,
        val encryptedData: ByteArray?,
    )

    fun parseRpcMessageData(bytes: ByteArray): RpcMessageData {
        val f = ProtoReader.fields(bytes)
        return RpcMessageData(
            sessionId = f[1]?.bytes?.toString(Charsets.UTF_8) ?: "",
            action = f[4]?.value?.toInt() ?: 0,
            unencryptedData = f[5]?.bytes,
            encryptedData = f[8]?.bytes,
        )
    }

    // =======================================================================
    // Decrypted event payloads (binary proto)
    // =======================================================================

    /** Conversation (conversations.proto), the subset the UI needs. */
    data class GMConversation(
        val conversationId: String,
        val name: String,
        val latestMessageText: String,
        val lastMessageTimestampMicros: Long,
        val unread: Boolean,
        val isGroupChat: Boolean,
        /** Participant id WE send as in this conversation (SendMessageRequest.participantID). */
        val defaultOutgoingId: String,
        val avatarHexColor: String,
        val participants: List<GMParticipant>,
        /** Participant ids excluding me. */
        val otherParticipantIds: List<String>,
    )

    /** Participant (conversations.proto). */
    data class GMParticipant(
        val participantId: String,
        val number: String,
        val firstName: String,
        val fullName: String,
        val avatarHexColor: String,
        val isMe: Boolean,
        val formattedNumber: String,
    )

    /** One reaction on a message: the emoji + who applied it. */
    data class GMReaction(
        val emoji: String,
        val participantIds: List<String>,
    )

    /** Media attachment on a message (image/video/etc). */
    data class GMMedia(
        val mediaId: String,
        val mimeType: String,
        val name: String,
        /** MediaFormats enum: 1–7 image, 8–13 video, 14–23 audio, … */
        val format: Int,
        /** Per-attachment AES-256-GCM key needed to decrypt the download. */
        val decryptionKey: ByteArray?,
    ) {
        val isImage: Boolean get() = format in 1..7 || mimeType.startsWith("image/")
        val isVideo: Boolean get() = format in 8..13 || mimeType.startsWith("video/")
    }

    /** Message (conversations.proto), the subset the UI needs. */
    data class GMMessage(
        val messageId: String,
        /** MessageStatusType — <100 outgoing, 100–199 incoming, 200+ tombstones, 300 deleted. */
        val statusCode: Int,
        val timestampMicros: Long,
        val conversationId: String,
        /** Sender's participant id. */
        val participantId: String,
        val text: String,
        /** Non-text parts present (media etc.) — rendered as a placeholder. */
        val hasMedia: Boolean,
        val tmpId: String,
        val replyToMessageId: String?,
        val reactions: List<GMReaction> = emptyList(),
        val media: GMMedia? = null,
        /** Diagnostic field dump set when media was present but unparsable. */
        val mediaDebug: String? = null,
    ) {
        val isOutgoing: Boolean get() = statusCode in 1..99
        val isTombstone: Boolean get() = statusCode in 200..299
        val isDeleted: Boolean get() = statusCode == 300
    }

    /**
     * UpdateEvents (events.proto) — what GET_UPDATES pushes decrypt to:
     *   conversationEvent=2 { data=2: repeated Conversation }
     *   messageEvent=3      { data=2: repeated Message }
     * (typing=4, settings=5, userAlert=6, browserPresenceCheck=7 ignored —
     *  except presence, which the caller must ack.)
     */
    data class UpdateEvents(
        val conversations: List<GMConversation>,
        val messages: List<GMMessage>,
        val isBrowserPresenceCheck: Boolean,
    )

    fun parseUpdateEvents(bytes: ByteArray): UpdateEvents {
        val conversations = ArrayList<GMConversation>()
        val messages = ArrayList<GMMessage>()
        var presence = false
        forEachField(bytes) { f ->
            when (f.number) {
                2 -> f.bytes?.let { ce -> // ConversationEvent
                    forEachField(ce) { inner ->
                        if (inner.number == 2) inner.bytes?.let { conversations.add(parseConversation(it)) }
                    }
                }
                3 -> f.bytes?.let { me -> // MessageEvent
                    forEachField(me) { inner ->
                        if (inner.number == 2) inner.bytes?.let { messages.add(parseMessage(it)) }
                    }
                }
                7 -> presence = true
            }
        }
        return UpdateEvents(conversations, messages, presence)
    }

    fun parseConversation(bytes: ByteArray): GMConversation {
        var id = ""; var name = ""; var latest = ""; var ts = 0L
        var unread = false; var group = false; var outgoingId = ""; var color = ""
        val participants = ArrayList<GMParticipant>()
        val others = ArrayList<String>()
        forEachField(bytes) { f ->
            when (f.number) {
                1 -> id = f.utf8()
                2 -> name = f.utf8()
                4 -> f.bytes?.let { latest = ProtoReader.fields(it)[1]?.bytes?.toString(Charsets.UTF_8) ?: "" }
                5 -> ts = f.value
                6 -> unread = f.value != 0L
                10 -> group = f.value != 0L
                11 -> outgoingId = f.utf8()
                15 -> color = f.utf8()
                20 -> f.bytes?.let { participants.add(parseParticipant(it)) }
                21 -> others.add(f.utf8())
            }
        }
        return GMConversation(id, name, latest, ts, unread, group, outgoingId, color, participants, others)
    }

    private fun parseParticipant(bytes: ByteArray): GMParticipant {
        var pid = ""; var number = ""; var first = ""; var full = ""
        var color = ""; var isMe = false; var formatted = ""
        forEachField(bytes) { f ->
            when (f.number) {
                1 -> f.bytes?.let { // SmallInfo { type=1, number=2, participantID=3 }
                    val si = ProtoReader.fields(it)
                    number = si[2]?.bytes?.toString(Charsets.UTF_8) ?: ""
                    pid = si[3]?.bytes?.toString(Charsets.UTF_8) ?: ""
                }
                2 -> first = f.utf8()
                3 -> full = f.utf8()
                5 -> color = f.utf8()
                6 -> isMe = f.value != 0L
                15 -> formatted = f.utf8()
            }
        }
        return GMParticipant(pid, number, first, full, color, isMe, formatted)
    }

    fun parseMessage(bytes: ByteArray): GMMessage {
        var id = ""; var status = 0; var ts = 0L; var convId = ""; var pid = ""
        var tmpId = ""; var replyTo: String? = null; var hasMedia = false
        var media: GMMedia? = null
        var mediaDebug: String? = null
        val textParts = ArrayList<String>()
        val reactions = ArrayList<GMReaction>()
        forEachField(bytes) { f ->
            when (f.number) {
                1 -> id = f.utf8()
                4 -> f.bytes?.let { status = (ProtoReader.fields(it)[2]?.value ?: 0L).toInt() }
                5 -> ts = f.value
                7 -> convId = f.utf8()
                9 -> pid = f.utf8()
                10 -> f.bytes?.let { mi -> // MessageInfo { messageContent=2 { content=1 }, mediaContent=3 }
                    val fields = ProtoReader.fields(mi)
                    fields[2]?.bytes?.let { mc ->
                        ProtoReader.fields(mc)[1]?.bytes?.toString(Charsets.UTF_8)?.let(textParts::add)
                    }
                    fields[3]?.bytes?.let { mediaBytes ->
                        hasMedia = true
                        val parsed = parseMediaContent(mediaBytes)
                        if (parsed != null) media = parsed
                        // Capture the actual field shape so an unparsed media
                        // attachment can be diagnosed (logged once by the repo).
                        else if (mediaDebug == null) mediaDebug = "mc{${describeFields(mediaBytes)}} mi{${describeFields(mi)}}"
                    }
                }
                12 -> tmpId = f.utf8()
                19 -> f.bytes?.let { re -> parseReactionEntry(re)?.let(reactions::add) }
                21 -> f.bytes?.let { replyTo = ProtoReader.fields(it)[1]?.bytes?.toString(Charsets.UTF_8) }
            }
        }
        return GMMessage(
            messageId = id, statusCode = status, timestampMicros = ts,
            conversationId = convId, participantId = pid,
            text = textParts.joinToString("\n"), hasMedia = hasMedia,
            tmpId = tmpId, replyToMessageId = replyTo, reactions = reactions, media = media,
            mediaDebug = mediaDebug,
        )
    }

    /** Compact dump of a message's immediate proto fields (number → short
     *  string / byte-length / varint). Used only to diagnose unparsed media. */
    private fun describeFields(bytes: ByteArray): String =
        ProtoReader.fields(bytes).entries.sortedBy { it.key }.joinToString(" ") { (n, field) ->
            val b = field.bytes
            if (b != null) {
                val s = b.toString(Charsets.UTF_8)
                if (s.isNotEmpty() && s.all { it.code in 32..126 } && s.length <= 48) "f$n='$s'"
                else "f$n=[${b.size}b]"
            } else "f$n=${field.value}"
        }

    /** MediaContent (conversations.proto): format=1, mediaID=2, mediaName=4,
     *  decryptionKey=11, mimeType=14. */
    private fun parseMediaContent(bytes: ByteArray): GMMedia? {
        val f = ProtoReader.fields(bytes)
        val mediaId = f[2]?.bytes?.toString(Charsets.UTF_8) ?: return null
        if (mediaId.isBlank()) return null
        return GMMedia(
            mediaId = mediaId,
            mimeType = f[14]?.bytes?.toString(Charsets.UTF_8) ?: "",
            name = f[4]?.bytes?.toString(Charsets.UTF_8) ?: "",
            format = (f[1]?.value ?: 0L).toInt(),
            decryptionKey = f[11]?.bytes,
        )
    }

    /**
     * DownloadAttachmentRequest (client.proto), binary proto, base64'd into
     * the x-goog-download-metadata header of a GET to /upload:
     *   { info=1: AttachmentInfo{attachmentID=1, encrypted=2}, authData=2: AuthMessage }
     */
    fun downloadAttachmentRequest(
        mediaId: String,
        requestId: String,
        tachyonAuthToken: ByteArray,
        encrypted: Boolean = true,
    ): ByteArray {
        // encrypted is a proto3 bool: omit when false (default), 1 when true.
        val info = ProtoWriter().string(1, mediaId).int32(2, if (encrypted) 1 else 0)
        val configVersion = ProtoWriter().int32(3, 2026).int32(4, 3).int32(5, 18).int32(7, 4).int32(9, 6)
        val auth = ProtoWriter()
            .string(1, requestId)
            // network (field 3) stays empty for QR/Bugle pairing
            .bytes(6, tachyonAuthToken)
            .message(7, configVersion)
        return ProtoWriter().message(1, info).message(2, auth).toByteArray()
    }

    /** ReactionEntry { data=1: ReactionData{unicode=1, type=2}, participantIDs=2 (repeated) }. */
    private fun parseReactionEntry(bytes: ByteArray): GMReaction? {
        var emoji = ""
        val pids = ArrayList<String>()
        forEachField(bytes) { f ->
            when (f.number) {
                1 -> f.bytes?.let { rd ->
                    val fields = ProtoReader.fields(rd)
                    val unicode = fields[1]?.bytes?.toString(Charsets.UTF_8) ?: ""
                    val type = (fields[2]?.value ?: 0L).toInt()
                    emoji = unicode.ifBlank { emojiTypeToUnicode(type) }
                }
                2 -> pids.add(f.utf8())
            }
        }
        if (emoji.isBlank()) return null
        return GMReaction(emoji, pids)
    }

    /**
     * SendReactionRequest (client.proto) — the plaintext payload for
     * ACTION_SEND_REACTION:
     *   { messageID=1, reactionData=2 { unicode=1, type=2 }, action=3 }
     * action: ADD=1, REMOVE=2.
     */
    fun sendReactionRequest(messageId: String, emoji: String, action: Int): ByteArray {
        val reactionData = ProtoWriter()
            .string(1, emoji)
            .int32(2, unicodeToEmojiType(emoji))
        return ProtoWriter()
            .string(1, messageId)
            .message(2, reactionData)
            .int32(3, action)
            .toByteArray()
    }

    /** SendReactionResponse { success=1 }. */
    fun parseSendReactionResponseSuccess(bytes: ByteArray): Boolean =
        (ProtoReader.fields(bytes)[1]?.value ?: 0L) != 0L

    // EmojiType (conversations.proto) ↔ unicode, per mautrix emojitype.go.
    // CUSTOM (8) carries the emoji in ReactionData.unicode instead.
    private val EMOJI_BY_TYPE = mapOf(
        1 to "👍", 2 to "😍", 3 to "😂", 4 to "😮", 5 to "😥", 6 to "😠",
        7 to "👎", 9 to "🤔", 10 to "😢", 11 to "😡", 12 to "❤️",
    )

    fun emojiTypeToUnicode(type: Int): String = EMOJI_BY_TYPE[type] ?: ""

    fun unicodeToEmojiType(emoji: String): Int = when (emoji) {
        "👍" -> 1; "😍" -> 2; "😂" -> 3; "😮" -> 4; "😥" -> 5; "😠" -> 6
        "👎" -> 7; "🤔" -> 9; "😢" -> 10; "😡" -> 11; "❤", "❤️" -> 12
        else -> 8 // CUSTOM
    }

    /** SendMessageResponse { status=3 } — 1 = SUCCESS. */
    fun parseSendMessageResponseStatus(bytes: ByteArray): Int =
        (ProtoReader.fields(bytes)[3]?.value ?: 0L).toInt()

    /** ListConversationsResponse { conversations=2: repeated Conversation }. */
    fun parseListConversationsResponse(bytes: ByteArray): List<GMConversation> {
        val out = ArrayList<GMConversation>()
        forEachField(bytes) { f -> if (f.number == 2) f.bytes?.let { out.add(parseConversation(it)) } }
        return out
    }

    /** ListMessagesResponse { messages=2: repeated Message }. */
    fun parseListMessagesResponse(bytes: ByteArray): List<GMMessage> {
        val out = ArrayList<GMMessage>()
        forEachField(bytes) { f -> if (f.number == 2) f.bytes?.let { out.add(parseMessage(it)) } }
        return out
    }

    /**
     * ListConversationsRequest { count=2, folder=4 (1=INBOX) }.
     * ListMessagesRequest      { conversationID=2, count=3 }.
     */
    fun listConversationsRequest(count: Int): ByteArray =
        ProtoWriter().int32(2, count).int32(4, 1).toByteArray()

    fun listMessagesRequest(conversationId: String, count: Int): ByteArray =
        ProtoWriter().string(2, conversationId).int32(3, count).toByteArray()

    /** Contact (conversations.proto) — phone-side address book entry. */
    data class GMContact(
        val participantId: String,
        val name: String,
        val number: String,
        val avatarHexColor: String,
    )

    /** ListContactsRequest { i1=5 (=1), i2=6 (=350), i3=7 (=50) } — magic
     *  values straight from mautrix methods.go ListContacts. */
    fun listContactsRequest(): ByteArray =
        ProtoWriter().int32(5, 1).int32(6, 350).int32(7, 50).toByteArray()

    /**
     * ListContactsResponse { contacts=2: repeated Contact }.
     * Contact { participantID=1, name=2, number=3: ContactNumber, avatarHexColor=7 }
     * ContactNumber { mysteriousInt=1, number=2, number2=3, formattedNumber=4 }
     */
    fun parseListContactsResponse(bytes: ByteArray): List<GMContact> {
        val out = ArrayList<GMContact>()
        forEachField(bytes) { f ->
            if (f.number != 2) return@forEachField
            val c = f.bytes ?: return@forEachField
            var pid = ""; var name = ""; var number = ""; var color = ""
            forEachField(c) { cf ->
                when (cf.number) {
                    1 -> pid = cf.utf8()
                    2 -> name = cf.utf8()
                    3 -> cf.bytes?.let { cn ->
                        // ContactNumber { mysteriousInt=1, number=2, number2=3,
                        // formattedNumber=4 }. Different contacts populate
                        // different fields, so take whichever is present —
                        // missing this is why some contacts dropped out of
                        // search entirely (blank number → filtered).
                        val fields = ProtoReader.fields(cn)
                        number = listOf(4, 2, 3)
                            .firstNotNullOfOrNull { fields[it]?.bytes?.toString(Charsets.UTF_8)?.takeIf(String::isNotBlank) }
                            ?: ""
                    }
                    7 -> color = cf.utf8()
                }
            }
            if (name.isNotBlank() || number.isNotBlank()) {
                out.add(GMContact(pid, name, number, color))
            }
        }
        return out
    }

    /**
     * GetOrCreateConversationRequest (client.proto):
     *   { numbers=2: repeated ContactNumber }
     *   ContactNumber { mysteriousInt=1 (2=contact, 7=user input), number=2, number2=3 }
     * mautrix sends mysteriousInt=2 with number==number2 for a DM.
     */
    fun getOrCreateConversationRequest(number: String): ByteArray =
        getOrCreateConversationRequest(listOf(number), null)

    /**
     * GetOrCreateConversationRequest { numbers=2 (repeated ContactNumber),
     * RCSGroupName=3, createRCSGroup=4 }. With one number this is a 1:1; with
     * several it asks the phone to create an RCS group (field 4 = true).
     */
    fun getOrCreateConversationRequest(numbers: List<String>, rcsGroupName: String?): ByteArray {
        val w = ProtoWriter()
        for (n in numbers) {
            // ContactNumber { type=1 (=2 PHONE), rawNumber=2, e164=3 }
            val contact = ProtoWriter().int32(1, 2).string(2, n).string(3, n)
            w.message(2, contact) // repeated field 2
        }
        if (numbers.size > 1) {
            if (!rcsGroupName.isNullOrBlank()) w.string(3, rcsGroupName)
            w.int32(4, 1) // createRCSGroup = true
        }
        return w.toByteArray()
    }

    /**
     * GetOrCreateConversationResponse { conversation=2: Conversation, status=3 }.
     * status: SUCCESS=1, CREATE_RCS=3. Returns the conversation (or null).
     */
    fun parseGetOrCreateConversationResponse(bytes: ByteArray): GMConversation? {
        val conv = ProtoReader.fields(bytes)[2]?.bytes ?: return null
        return parseConversation(conv)
    }

    // =======================================================================
    // Outgoing: binary inner payloads
    // =======================================================================

    /**
     * OutgoingRPCData (rpc.proto):
     *   requestID=1, action=2, unencryptedProtoData=3, encryptedProtoData=5, sessionID=6
     */
    fun outgoingRpcData(
        requestId: String,
        action: Int,
        encryptedProtoData: ByteArray?,
        sessionId: String,
    ): ByteArray = ProtoWriter()
        .string(1, requestId)
        .int32(2, action)
        .bytes(5, encryptedProtoData)
        .string(6, sessionId)
        .toByteArray()

    /**
     * SendMessageRequest (client.proto) — the plaintext that gets encrypted
     * into OutgoingRPCData.encryptedProtoData for ACTION_SEND_MESSAGE:
     *
     *   SendMessageRequest { conversationID=2, messagePayload=3, tmpID=5, reply=8 }
     *   MessagePayload    { tmpID=1, conversationID=7, participantID=9,
     *                       messageInfo=10 (repeated), tmpID2=12 }
     *   MessageInfo       { messageContent=2 { content=1 } }
     *   ReplyPayload      { messageID=1 }
     */
    fun sendMessageRequest(
        conversationId: String,
        text: String,
        tmpId: String,
        /** Conversation.defaultOutgoingID — the participant we send as. */
        participantId: String,
        replyToMessageId: String? = null,
    ): ByteArray {
        val messageInfo = ProtoWriter().message(
            2,
            ProtoWriter().string(1, text), // MessageContent { content=1 }
        )
        val payload = ProtoWriter()
            .string(1, tmpId)
            .string(7, conversationId)
            .string(9, participantId)
            .message(10, messageInfo)
            .string(12, tmpId)
        val req = ProtoWriter()
            .string(2, conversationId)
            .message(3, payload)
            .string(5, tmpId)
        if (replyToMessageId != null) {
            req.message(8, ProtoWriter().string(1, replyToMessageId))
        }
        return req.toByteArray()
    }

    /** MessageReadRequest { conversationID=2, messageID=3 }. */
    fun messageReadRequest(conversationId: String, messageId: String): ByteArray =
        ProtoWriter().string(2, conversationId).string(3, messageId).toByteArray()

    // --- Media upload --------------------------------------------------------

    /** StartMediaUploadRequest { attachmentType=1 (=1), authData=2: AuthMessage,
     *  mobile=3: Device }. Sent (base64) as the /upload "start" body. */
    fun startMediaUploadRequest(
        requestId: String,
        tachyonAuthToken: ByteArray,
        mobile: GMDeviceInfo,
    ): ByteArray {
        val configVersion = ProtoWriter().int32(3, 2026).int32(4, 3).int32(5, 18).int32(7, 4).int32(9, 6)
        val auth = ProtoWriter()
            .string(1, requestId)
            .bytes(6, tachyonAuthToken)
            .message(7, configVersion)
        val device = ProtoWriter()
            // userID is an int64 — Google account ids exceed Int range, so
            // .toInt() truncated the token's device id and the upload endpoint
            // rejected the auth with HTTP 401. Write the full Long varint.
            .varint(1, mobile.userId)
            .string(2, mobile.sourceId)
            .string(3, mobile.network)
        return ProtoWriter().int32(1, 1).message(2, auth).message(3, device).toByteArray()
    }

    /** UploadMediaResponse { media=1: UploadedMedia { mediaID=1, mediaNumber=2 } }. */
    fun parseUploadMediaResponse(bytes: ByteArray): String? {
        val media = ProtoReader.fields(bytes)[1]?.bytes ?: return null
        return ProtoReader.fields(media)[1]?.bytes?.toString(Charsets.UTF_8)
    }

    /** MediaFormats enum value for a mime type (subset; falls back to the
     *  *_UNSPECIFIED for the category). */
    fun mediaFormatForMime(mime: String): Int = when (mime.lowercase()) {
        "image/jpeg" -> 1
        "image/jpg" -> 2
        "image/png" -> 3
        "image/gif" -> 4
        "video/mp4" -> 8
        "video/3gpp" -> 10
        "video/webm" -> 11
        "video/x-matroska", "video/mkv" -> 12
        else -> when {
            mime.startsWith("image/") -> 7  // IMAGE_UNSPECIFIED
            mime.startsWith("video/") -> 13 // VIDEO_UNSPECIFIED
            else -> 0
        }
    }

    /** MediaContent { format=1, mediaID=2, mediaName=4, size=5, decryptionKey=11,
     *  mimeType=14 }. */
    private fun mediaContent(
        mediaId: String,
        format: Int,
        name: String,
        size: Long,
        decryptionKey: ByteArray,
        mime: String,
    ): ProtoWriter = ProtoWriter()
        .int32(1, format)
        .string(2, mediaId)
        .string(4, name)
        .varint(5, size)
        .bytes(11, decryptionKey)
        .string(14, mime)

    /**
     * SendMessageRequest carrying a media attachment instead of text:
     * MessagePayload.messageInfo = [ MessageInfo { mediaContent=3 } ].
     */
    fun sendMediaMessageRequest(
        conversationId: String,
        tmpId: String,
        participantId: String,
        mediaId: String,
        mediaName: String,
        size: Long,
        decryptionKey: ByteArray,
        mime: String,
    ): ByteArray {
        val media = mediaContent(mediaId, mediaFormatForMime(mime), mediaName, size, decryptionKey, mime)
        val messageInfo = ProtoWriter().message(3, media) // MessageInfo.mediaContent
        val payload = ProtoWriter()
            .string(1, tmpId)
            .string(7, conversationId)
            .string(9, participantId)
            .message(10, messageInfo)
            .string(12, tmpId)
        return ProtoWriter()
            .string(2, conversationId)
            .message(3, payload)
            .string(5, tmpId)
            .toByteArray()
    }

    /** NotifyDittoActivityRequest { success=2 }. */
    fun notifyDittoActivityRequest(): ByteArray =
        ProtoWriter().int32(2, 1).toByteArray()

    // =======================================================================
    // Outgoing: pblite envelopes
    // =======================================================================

    private fun deviceJson(d: GMDeviceInfo): String =
        "[${d.userId},${PbLite.jsonString(d.sourceId)},${PbLite.jsonString(d.network)}]"

    /**
     * OutgoingRPCMessage (rpc.proto) as pblite, POSTed to Messaging/SendMessage:
     *
     *   mobile=1 (Device), data=2, auth=3, TTL=5, destRegistrationIDs=9 (unused)
     *   data: { requestID=1, bugleRoute=2 (19=DataEvent), messageData=12
     *           (OutgoingRPCData bytes), messageTypeData=23 { emptyArr=1, messageType=2 } }
     *   auth: { requestID=1, tachyonAuthToken=6, configVersion=7 }
     */
    fun outgoingRpcMessage(
        mobile: GMDeviceInfo,
        requestId: String,
        messageData: ByteArray,
        messageType: Int,
        tachyonAuthToken: ByteArray,
        ttl: Long,
        /** Google-account (GAIA) mode: the primary phone's registration id
         *  (base64 of the UUID string). Goes in destRegistrationIDs (field 9). */
        destRegB64: String? = null,
    ): String {
        val rid = PbLite.jsonString(requestId)
        val data = buildString {
            append("[").append(rid).append(",").append(ROUTE_DATA_EVENT)
            repeat(9) { append(",null") }                       // idx 2..10
            append(",").append(PbLite.jsonString(B64.encode(messageData))) // idx 11
            repeat(10) { append(",null") }                      // idx 12..21
            append(",[[],").append(messageType).append("]")     // idx 22 messageTypeData
            append("]")
        }
        val auth = "[$rid,null,null,null,null," +
            "${PbLite.jsonString(B64.encode(tachyonAuthToken))},$CONFIG_VERSION_PBLITE]"
        val ttlJson = if (ttl != 0L) "$ttl" else "null"
        val destReg = if (destRegB64 != null) "[${PbLite.jsonString(destRegB64)}]" else "null"
        return "[${deviceJson(mobile)},$data,$auth,null,$ttlJson,null,null,null,$destReg]"
    }

    /**
     * AckMessageRequest (client.proto) as pblite, POSTed to Messaging/AckMessages:
     *   authData=1, emptyArr=2, acks=4 (repeated { requestID=1, device=2 })
     */
    fun ackMessageRequest(
        requestId: String,
        tachyonAuthToken: ByteArray,
        browser: GMDeviceInfo,
        ackIds: List<String>,
        network: String? = null,
    ): String {
        val networkJson = if (network != null) PbLite.jsonString(network) else "null"
        val auth = "[${PbLite.jsonString(requestId)},null,$networkJson,null,null," +
            "${PbLite.jsonString(B64.encode(tachyonAuthToken))},$CONFIG_VERSION_PBLITE]"
        val acks = ackIds.joinToString(",") {
            "[${PbLite.jsonString(it)},${deviceJson(browser)}]"
        }
        return "[$auth,[],null,[$acks]]"
    }

    /**
     * RegisterRefreshRequest (authentication.proto) as pblite, POSTed to
     * Registration/RegisterRefresh — renews the tachyon token:
     *
     *   messageAuth=1, currBrowserDevice=2, unixTimestamp=3, signature=4,
     *   parameters=13 { emptyArr=9 }, messageType=16 (=2)
     *
     * @param signature ECDSA P-256 ASN.1 over SHA-256("requestID:timestamp")
     *        (mautrix client.go refreshAuthToken).
     */
    fun registerRefreshRequest(
        requestId: String,
        tachyonAuthToken: ByteArray,
        browser: GMDeviceInfo,
        unixTimestampMicros: Long,
        signature: ByteArray,
        network: String? = null,
    ): String {
        val networkJson = if (network != null) PbLite.jsonString(network) else "null"
        val auth = "[${PbLite.jsonString(requestId)},null,$networkJson,null,null," +
            "${PbLite.jsonString(B64.encode(tachyonAuthToken))},$CONFIG_VERSION_PBLITE]"
        // Parameters: 23 slots, idx8 = emptyArr.
        val params = buildString {
            append("[")
            repeat(8) { append("null,") }
            append("[]")
            repeat(14) { append(",null") }
            append("]")
        }
        return "[$auth,${deviceJson(browser)},$unixTimestampMicros," +
            "${PbLite.jsonString(B64.encode(signature))}," +
            "null,null,null,null,null,null,null,null,$params,null,null,2]"
    }

    /** RegisterRefreshResponse { tokenData=2: TokenData { token=1, TTL=2 } }. */
    data class RefreshedToken(val tachyonAuthToken: ByteArray, val ttl: Long)

    fun parseRegisterRefreshResponse(pbliteBody: String): RefreshedToken? {
        val node = PbLite.parse(pbliteBody) as? PbLite.Node.Arr ?: return null
        val tokenData = node[1] as? PbLite.Node.Arr ?: return null
        val token = tokenData[0].asStringOrNull()?.let(B64::decode) ?: return null
        return RefreshedToken(token, tokenData[1].asLongOrNull() ?: 0L)
    }

    // --- helpers -----------------------------------------------------------

    /** Iterate ALL fields (incl. repeated) — [ProtoReader.fields] is last-wins. */
    private inline fun forEachField(buf: ByteArray, block: (ProtoReader.Field) -> Unit) {
        val r = ProtoReader(buf)
        while (r.hasNext()) block(r.readField())
    }

    private fun ProtoReader.Field.utf8(): String = bytes?.toString(Charsets.UTF_8) ?: ""
}
