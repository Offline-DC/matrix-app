plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // This module ships Compose UI (the setup/status screen + the chat gate),
    // so it needs the Compose compiler plugin — unlike the headless backend
    // modules. Same as :gmessages in matrix-app.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offline.dpadmessenger.backend.smarttxt"
    compileSdk = 36
    defaultConfig { minSdk = 24 }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Let plain JVM unit tests run without Robolectric: android.util.Log etc.
    // return defaults (0/null) instead of throwing "not mocked". The relay /
    // validation-data tests only need this much.
    testOptions { unitTests.isReturnDefaultValues = true }

    // NOTE(Phase B): the rustpush `.so` will land in src/main/jniLibs/{arm64-v8a,
    // armeabi-v7a}/libsmarttxt_ffi.so. The directory is created and `.gitkept`
    // so the build wiring exists before the native build does. Nothing loads
    // it yet — RustPushBridge runs in stub mode (see RustPushBridge.kt).
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // The reused chat UI (Compose screens, MessageRepository contract) via the
    // composite-build wiring in settings.gradle.kts. Exposed as `api` so the
    // host app gets the UI library transitively, exactly like :gmessages.
    api("com.offline.dpadmessenger:library:0.2.0")

    // :core provides the BackendConfig sealed class + BackendFactory interface
    // that SmartTxtBackendFactory implements (same as :gmessages / :signal).
    api(project(":core"))

    // EncryptedSharedPreferences for the "dumb file" (serialized MacOSConfig)
    // + IDS keys + Apple ID tokens + renewal timestamp.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // OkHttp for the (mockable) ValidationDataRelay HTTP skeleton + future
    // direct Apple endpoints.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WorkManager drives SmartTxtRenewalWorker (periodic re-registration).
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines for the receive loop + the suspending MessageRepository API.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.core:core-ktx:1.12.0")

    // kotlinx-serialization to (de)serialize the MacOSConfig "dumb file" blob,
    // matching the Serialize/Deserialize derive on rustpush's MacOSConfig.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}
