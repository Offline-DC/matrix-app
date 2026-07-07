package com.offline.dpadmessenger.backend.matrix

import android.content.Context
import com.offline.dpadmessenger.backend.core.BackendConfig
import com.offline.dpadmessenger.backend.core.BackendFactory
import com.offline.dpadmessenger.data.MessageRepository
import java.io.File

/**
 * [BackendFactory] that produces [MatrixMessageRepository] instances.
 *
 * For [BackendConfig.RemoteMatrix]: tries to restore an existing session
 * first; if none exists the caller must run a login flow (see the login
 * screen wired in the UI repo's demo app) and then re-call.
 *
 * For [BackendConfig.EmbeddedConduit]: same but assumes the embedded
 * homeserver service is already running. See the conduit module's README.
 */
class MatrixBackendFactory : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        val homeserver = when (config) {
            is BackendConfig.RemoteMatrix -> config.homeserverUrl
            is BackendConfig.EmbeddedConduit -> "http://127.0.0.1:${config.port}"
            is BackendConfig.SignalBridge -> "http://127.0.0.1:${config.conduitPort}"
            // Not a Matrix config (Mock / SignalDirect / SmartTxtNative): this
            // factory doesn't handle it — the caller falls through to the right
            // one. `else` also keeps this `when` exhaustive as new BackendConfig
            // variants are added.
            else -> return null
        }
        val sessionDir = File(context.filesDir, "matrix-sessions")
        val auth = MatrixAuth(context, sessionDir)
        val client = auth.restore() ?: return null  // not logged in
        // Defensive: verify the persisted session points at the configured
        // homeserver. If not, treat as not logged in (caller can re-login).
        if (client.session().homeserverUrl != homeserver) return null
        return MatrixMessageRepository(client)
    }
}
