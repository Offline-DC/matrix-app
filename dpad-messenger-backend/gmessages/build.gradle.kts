plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ moved the Compose Compiler into its own plugin. This module
    // ships Compose UI (the QR link screen + the pairing/chat gate), so it
    // needs the plugin applied — unlike the other backend modules (core,
    // signal, matrix), which are headless.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offline.dpadmessenger.backend.gmessages"
    compileSdk = 36
    defaultConfig { minSdk = 24 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests {
            // Let plain-JVM tests exercise code that logs. Without this, any android.util.Log
            // call on a tested path throws "Method i in android.util.Log not mocked", which in
            // this module rules out nearly everything worth testing — the credential paths are
            // deliberately log-heavy because a customer's logcat is the only telemetry we get.
            // Defaults are enough here: we assert on returned values, never on log output.
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // The chat UI (Compose screens, MessageRepository contract). Pulled in
    // via the composite-build wiring in dpad-messenger-backend/settings.gradle.kts
    // (the `com.offline.dpadmessenger:library` coordinate is substituted for
    // the sibling dpad-messenger `:library` project). Exposed as `api` so the
    // host app (e.g. dumb-down-launcher) that consumes this module also gets
    // the UI library — DpadMessengerTheme, the MessageRepository interface,
    // and the chat screens — on its classpath without declaring it twice.
    api("com.offline.dpadmessenger:library:0.2.0")

    // :core owns the shared SQLite MessageStore that replaced the encrypted-JSON
    // blob cache. :smarttxt and :signal already depended on :core; this module was
    // the odd one out. Adds nothing new transitively — library, security-crypto,
    // core-ktx and coroutines are all already declared here at the same versions.
    api(project(":core"))

    // EncryptedSharedPreferences for persisting the pairing tokens we get
    // back from the QR pairing flow with the user's primary phone.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for the relay WebSocket + RPC calls to Google's instantmessaging
    // backend. Same library the Signal backend uses; keeps trust-handling
    // simple.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines for the receive loop + the suspending API surface that
    // MessageRepository expects.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")

    // ZXing (pure-Java core) to ENCODE the pairing QR the user scans with
    // their primary phone.
    implementation("com.google.zxing:core:3.5.3")

    // X25519 for the pairing handshake is implemented in-repo (X25519.kt,
    // a pure-Kotlin TweetNaCl port) — no crypto dependency needed.

    // RFC 7748 test vectors for X25519.kt, plus the protobuf/pblite/crypto
    // unit tests (30 tests total).
    testImplementation("junit:junit:4.13.2")
}

// NOTE(Phase B.2): if/when the hand-rolled protobuf in GMSessionProto.kt /
// GMPairingProto.kt is replaced by generated code, add the protobuf-gradle
// plugin block here. The sibling signal/build.gradle.kts has a working
// protobuf-javalite reference configuration to mirror.
