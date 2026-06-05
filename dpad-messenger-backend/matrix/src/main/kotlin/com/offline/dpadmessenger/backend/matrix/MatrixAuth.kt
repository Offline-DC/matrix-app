package com.offline.dpadmessenger.backend.matrix

import android.content.Context
import com.offline.dpadmessenger.backend.core.PersistedSession
import com.offline.dpadmessenger.backend.core.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import java.io.File

/**
 * Login / logout / session-restore against a Matrix homeserver via
 * matrix-rust-sdk.
 *
 * Stateless aside from disk: the Rust SDK keeps its own SQLite-backed store
 * under [sessionDir]. We keep a parallel record of the access token + user
 * id in [SessionStore] so we can quickly check "is the user logged in?"
 * before bringing the SDK up.
 *
 * @param sessionDir base path the SDK uses for its own DB. Use the app's
 *        private files dir (e.g. `context.filesDir / "matrix"`) so it's
 *        wiped on uninstall.
 */
class MatrixAuth(
    private val context: Context,
    private val sessionDir: File,
    private val sessionStore: SessionStore = SessionStore(context),
) {

    /**
     * Build a Client configured for [homeserverUrl] and call
     * `m.login.password` with the provided credentials. On success, the
     * session is persisted to [sessionStore] and the live [Client] is
     * returned ready for use.
     *
     * The Rust SDK handles homeserver discovery (well-known lookup); we
     * just pass the URL the user typed.
     */
    suspend fun login(
        homeserverUrl: String,
        username: String,
        password: String,
    ): Client = withContext(Dispatchers.IO) {
        sessionDir.mkdirs()
        val client = ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sessionPath(sessionDir.absolutePath)
            .build()

        // Standard password login. SDK version-specific: some versions
        // require an initial device display name; we pass a stable one.
        client.login(
            username = username,
            password = password,
            initialDeviceName = DEVICE_NAME,
            deviceId = null,
        )

        val session = client.session()
        sessionStore.save(
            PersistedSession(
                homeserverUrl = homeserverUrl,
                userId = session.userId,
                accessToken = session.accessToken,
                deviceId = session.deviceId,
                refreshToken = session.refreshToken,
            )
        )
        client
    }

    /**
     * Re-hydrate a Client from a previously-persisted session. Returns
     * null if the user has never logged in (or [SessionStore] is empty).
     */
    suspend fun restore(): Client? = withContext(Dispatchers.IO) {
        val persisted = sessionStore.load() ?: return@withContext null
        sessionDir.mkdirs()
        val client = ClientBuilder()
            .homeserverUrl(persisted.homeserverUrl)
            .sessionPath(sessionDir.absolutePath)
            .build()
        client.restoreSession(
            org.matrix.rustcomponents.sdk.Session(
                accessToken = persisted.accessToken,
                refreshToken = persisted.refreshToken,
                userId = persisted.userId,
                deviceId = persisted.deviceId,
                homeserverUrl = persisted.homeserverUrl,
                oidcData = null,
                slidingSyncProxy = null,
            )
        )
        client
    }

    suspend fun logout(client: Client) = withContext(Dispatchers.IO) {
        runCatching { client.logout() }
        sessionStore.clear()
    }

    companion object {
        const val DEVICE_NAME = "DPAD Messenger"
    }
}
