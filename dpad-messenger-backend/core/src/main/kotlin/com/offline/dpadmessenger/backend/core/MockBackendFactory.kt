package com.offline.dpadmessenger.backend.core

import android.content.Context
import com.offline.dpadmessenger.data.InMemoryMessageRepository
import com.offline.dpadmessenger.data.MessageRepository

/**
 * [BackendFactory] that produces the UI library's [InMemoryMessageRepository]
 * seeded from `assets/mock_data.json`. This is the always-available default
 * — no Matrix server required, no login flow, no network. Useful for:
 *
 *   - shipping a demo build of the wired app
 *   - validating the full UI ↔ factory ↔ navigation wiring before
 *     enabling a real Matrix backend
 *   - offline UI iteration
 *
 * Only responds to [BackendConfig.Mock]; other configs return null so the
 * caller falls through to the right factory.
 */
class MockBackendFactory(
    private val mockAsset: String = "mock_data.json",
) : BackendFactory {
    override suspend fun create(context: Context, config: BackendConfig): MessageRepository? {
        return when (config) {
            BackendConfig.Mock -> InMemoryMessageRepository.fromAssets(context, mockAsset)
            else -> null
        }
    }
}
