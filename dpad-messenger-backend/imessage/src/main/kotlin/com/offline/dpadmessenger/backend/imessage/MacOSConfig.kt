package com.offline.dpadmessenger.backend.imessage

import kotlinx.serialization.Serializable

/**
 * The "dumb file": a serialized rustpush `MacOSConfig`.
 *
 * Per IMESSAGE_NATIVE_BACKEND_PLAN.md §2.1, rustpush's auth all hangs off one
 * trait — `OSConfig`, implemented by `MacOSConfig` — and the dumb file *is* a
 * serialized `MacOSConfig`. OpenBubbles persists and reloads it straight
 * to/from a file because the struct derives `Serialize`/`Deserialize`; we copy
 * that mechanism rather than invent one. [IMessageAccountStore] holds this blob
 * (encrypted) and hands a deserialized copy to [RustPushBridge] as the
 * `&dyn OSConfig`.
 *
 * The Rust struct (for reference):
 * ```
 * pub struct MacOSConfig {
 *     pub inner: HardwareConfig,   // from open-absinthe::nac
 *     pub version: String,         // e.g. "13.6.4"
 *     pub protocol_version: u32,
 *     pub device_id: String,
 *     pub icloud_ua: String,
 *     pub aoskit_version: String,
 *     pub udid: Option<String>,
 * }
 * ```
 *
 * NOTE: the [HardwareConfig] fields below are the open part — they're visible
 * in the OpenAbsinthe-Stub. What's NOT open is the engine that turns these
 * identifiers into Apple-accepted validation data (the absinthe `nac`); that
 * lives behind [com.offline.dpadmessenger.backend.imessage.absinthe.AbsintheStub]
 * and is supplied at runtime by a `ValidationDataRelay` for now.
 */
@Serializable
data class MacOSConfig(
    val inner: HardwareConfig,
    /** macOS version string presented to Apple, e.g. "13.6.4". */
    val version: String,
    val protocolVersion: Int,
    val deviceId: String,
    val icloudUa: String,
    val aoskitVersion: String,
    val udid: String? = null,
) {
    companion object {
        /**
         * A clearly-fake config for stub/demo mode. It has the right SHAPE so
         * the setup screen, the account store, and the relay seam all exercise
         * real code paths — but it is NOT a usable Mac identity. The real dumb
         * file is imported from OB's hardware-info store (out of scope here).
         */
        fun placeholder(): MacOSConfig = MacOSConfig(
            inner = HardwareConfig.placeholder(),
            version = "13.6.4",
            protocolVersion = 1640,
            deviceId = "00000000-0000-0000-0000-000000000000",
            icloudUa = "com.apple.iCloudHelper/282 CFNetwork/1408.0.4 Darwin/22.5.0",
            aoskitVersion = "com.apple.AOSKit/282 (com.apple.accountsd/113)",
            udid = null,
        )
    }
}

/**
 * `open_absinthe::nac::HardwareConfig` — the hardware identity of a specific
 * Mac. These fields are open (visible in the stub); the *use* of them to mint
 * validation data is the closed part.
 */
@Serializable
data class HardwareConfig(
    val platformSerialNumber: String,
    /** Main logic board serial (X-Apple-I-MLB). */
    val mlb: String,
    /** ROM / MAC-derived value (X-Apple-I-ROM). */
    val rom: String,
    val productName: String,
    val osBuildNum: String,
    val deviceClass: String = "Mac",
) {
    companion object {
        fun placeholder(): HardwareConfig = HardwareConfig(
            platformSerialNumber = "C02XXXXXXXXX",
            mlb = "C02XXXXXXXXXXXXXX",
            rom = "000000000000",
            productName = "MacBookPro18,3",
            osBuildNum = "22G513",
        )
    }
}

/**
 * The Apple ID account tokens produced by rustpush's `authenticate_apple`
 * (GrandSlam / GSA + Anisette). Captured once (in-app or seeded from a
 * real-device registration) and persisted alongside the dumb file. Combined
 * with validation data + the activation cert during IDS `register`.
 */
@Serializable
data class IMessageAccount(
    /** The Apple ID (email) this identity is registered to. */
    val appleId: String,
    /** Opaque GSA/IDS token blob (base64). Stub data in mock mode. */
    val identityTokenB64: String,
    /** APNs push token from device activation (base64). */
    val pushTokenB64: String,
    /** Unix-millis when the IDS registration was last refreshed. 0 = never. */
    val lastRegisteredMs: Long = 0L,
    /** Handles this identity can send as, e.g. ["mailto:me@icloud.com",
     *  "tel:+15551234567"]. */
    val handles: List<String> = emptyList(),
)
