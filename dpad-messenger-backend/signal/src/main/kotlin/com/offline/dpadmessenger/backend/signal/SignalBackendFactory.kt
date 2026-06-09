package com.offline.dpadmessenger.backend.signal

import android.content.Context
import android.util.Log
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires [SignalMessageRepository] for [BackendConfig.SignalDirect].
 *
 * Returns null when no Signal account is linked yet — the app should
 * surface the link flow (see `app/SignalLinkScreen`).
 */
class SignalBackendFactory : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        if (config !is BackendConfig.SignalDirect) return null
        val account = SignalAccountStore(context).load() ?: return null
        // The sender talks to the same Signal API the link flow used —
        // building a fresh OkHttp here keeps things simple; cert/key
        // caching lives inside SignalSender itself.
        val api = SignalApi(SignalTrust.buildOkHttp(context))
        val groups = SignalGroups(account, api)
        val sender = SignalSender(context, account, api, groups)
        val attachments = SignalAttachments(context, account, api)
        val store = SignalMessageStore(context)
        val profiles = SignalProfiles(account, api)
        val repo = SignalMessageRepository(account, sender, attachments, groups, store, profiles)
        // Let the sender echo each conversation's disappearing-messages timer.
        sender.conversationTimerLookup = repo::expireTimerFor
        // Bring up the inbound chat WebSocket so the repo can receive. We do
        // NOT request contact sync — modern primaries don't answer it, and the
        // request only rate-limited (429) the /v2/keys/<own-aci> endpoint.
        // Contact names come from profile fetch (SignalProfiles) instead.
        val socket = SignalChatWebSocket(context, account, repo)
        socket.connect()
        return repo
    }

    companion object {
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
