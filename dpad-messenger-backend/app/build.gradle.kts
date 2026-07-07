plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Kotlin 2.0+ moved the Compose Compiler out of the Kotlin compiler
    // and into its own plugin. Without this you'll see
    // "compose compiler not found" or runtime failures in @Composable.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offline.dpadmessenger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offline.dpadmessenger.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }
    // kotlinCompilerExtensionVersion was removed under Kotlin 2.0+;
    // the version of the Compose Compiler now follows the Kotlin
    // version via the `org.jetbrains.kotlin.plugin.compose` plugin.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // libsignal-android uses java.time + other Java 8+ APIs that need
        // backporting on older Android versions.
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // UI library from the sibling repo via composite build.
    implementation("com.offline.dpadmessenger:library:0.2.0")

    // Backend modules.
    implementation(project(":core"))
    // Signal direct-mode backend (Phase 5). Real libsignal-android calls,
    // QR provisioning flow. See docs/SIGNAL_BRIDGE.md for what's wired
    // and what still needs protobuf glue.
    implementation(project(":signal"))

    // Native SmartTxt backend. The Signal-like shared UI drives it; the actual
    // Apple registration (dumb file + Apple ID + 2FA) runs in the smarttxt-relay
    // daemon (OpenBubbles-style), which this connects to over the relay WebSocket.
    implementation(project(":smarttxt"))

    // -- Real Matrix backend ----------------------------------------------
    // Uncomment ONLY after verifying matrix-rust-sdk-android compiles
    // against your installed version (see docs/MATRIX_BACKEND_STATUS.md).
    // The app boots fine without this — it falls back to the mock backend.
    //
    // implementation(project(":matrix"))
    // ----------------------------------------------------------------------

    // QR code generation for the Signal device-link flow.
    implementation("com.google.zxing:core:3.5.2")

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Required by libsignal-android.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
