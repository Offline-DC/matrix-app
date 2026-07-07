package com.offline.dpadmessenger.backend.smarttxt

import android.content.Context
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.data.MessageRepository

/**
 * [BackendFactory] for the native SmartTxt backend. Mirror of
 * `SignalBackendFactory`.
 *
 * Returns the process-scoped SmartTxt repository for
 * [BackendConfig.SmartTxtNative]; null for anything else so the caller falls
 * through to the right factory. The repository renders the chat UI whether or
 * not registration has completed — the UI gates the chat behind
 * [SmartTxtRepository.status] (see `ui/SmartTxtApp`).
 */
class SmartTxtBackendFactory : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        return when (config) {
            BackendConfig.SmartTxtNative -> SmartTxtRepository.create(context)
            else -> null
        }
    }
}
