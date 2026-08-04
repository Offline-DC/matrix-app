package com.offline.dpadmessenger.backend.smarttxt.transport

import kotlinx.serialization.json.Json

/**
 * The relay wire protocol: newline-free JSON frames over a single WebSocket.
 * Documented here so the relay server (the piece Jack is providing) and this
 * client agree on the contract. Versioned via [SOCKET_PATH].
 *
 * Every client→relay request carries a `type` and a numeric `id`. The relay
 * answers with `{"type":"ack","id":<same>,"ok":<bool>,"data":<payload>}`. The
 * relay also pushes unsolicited frames (no `id`) for live events:
 * `new_message`, `message_status`, `tapback`, `typing`, `chat_updated`,
 * `auth_expired`.
 *
 * Request types: register, get_chats, get_messages, send_text, send_tapback,
 * edit_message, unsend_message, mark_read, set_typing, get_contacts,
 * create_chat, send_attachment, download_attachment, ping.
 */
object RelayProtocol {
    const val SOCKET_PATH = "smarttxt/v1/socket"

    // request types
    const val T_REGISTER = "register"
    const val T_GET_CHATS = "get_chats"
    const val T_GET_MESSAGES = "get_messages"
    const val T_SEND_TEXT = "send_text"
    const val T_SEND_TAPBACK = "send_tapback"
    const val T_EDIT = "edit_message"
    const val T_UNSEND = "unsend_message"
    const val T_MARK_READ = "mark_read"
    const val T_SET_TYPING = "set_typing"
    const val T_GET_CONTACTS = "get_contacts"
    const val T_CREATE_CHAT = "create_chat"
    const val T_SEND_ATTACHMENT = "send_attachment"
    const val T_DOWNLOAD_ATTACHMENT = "download_attachment"
    const val T_REAUTH = "reauth"
    const val T_PING = "ping"

    // push types
    const val P_NEW_MESSAGE = "new_message"
    const val P_MESSAGE_STATUS = "message_status"
    const val P_TAPBACK = "tapback"
    /** A chat was read on another of my devices → clear its unread + notification. */
    const val P_CHAT_READ = "chat_read"
    const val P_TYPING = "typing"
    const val P_CHAT_UPDATED = "chat_updated"
    const val P_AUTH_EXPIRED = "auth_expired"

    /** rustpush's own IDS registration state, pushed from its `resource_state`
     *  watch channel by the native side (see `spawn_regstate_watcher`). */
    const val P_REGISTRATION_STATE = "registration_state"

    /** Apple has refused this device's APS connect enough times in a row that the push
     *  certificate has to be considered dead. The socket still transmits, so sends
     *  appear to work — nothing is ever delivered. Only the user can fix it. */
    const val P_PUSH_CERT_REJECTED = "push_cert_rejected"

    const val T_ACK = "ack"

    /** Lenient JSON shared by all transports (forward-compatible with relay
     *  fields we don't model yet). */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }
}
