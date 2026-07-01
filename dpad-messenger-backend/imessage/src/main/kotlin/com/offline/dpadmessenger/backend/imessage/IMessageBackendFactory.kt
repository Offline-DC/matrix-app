package com.offline.dpadmessenger.backend.imessage

import android.content.Context
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.data.MessageRepository

/**
 * [BackendFactory] for the native iMessage backend. Mirror of
 * `SignalBackendFactory`.
 *
 * Returns the process-scoped iMessage repository for
 * [BackendConfig.IMessageNative]; null for anything else so the caller falls
 * through to the right factory. The repository renders the chat UI whether or
 * not registration has completed — the UI gates the chat behind
 * [IMessageRepository.status] (see `ui/IMessageApp`).
 */
class IMessageBackendFactory : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        return when (config) {
            BackendConfig.IMessageNative -> IMessageRepository.create(context)
            else -> null
        }
    }
}
