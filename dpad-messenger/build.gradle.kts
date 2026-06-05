// Root build file. Keep plugin declarations centralized here.
//
// Versions bumped in lockstep with the backend repo's libsignal-android
// 0.86.5 upgrade: that artifact ships Kotlin 2.1.0 metadata and a
// transitive kotlinx-coroutines 1.10 that won't be read by older Kotlin
// or dexed by older R8. AGP 8.7 bundles R8 8.7 (handles Kotlin 2.1
// metadata); AGP 8.7 needs Gradle 8.9+ — wrapper bumped to 8.10.2.
plugins {
    // Bumped 8.7.3 → 8.13.2 to align with the dumb-down-launcher repo,
    // which composite-includes this UI library. Gradle composite builds
    // require all included builds to use the SAME AGP version (else:
    // "Using multiple versions of the Android Gradle Plugin
    // [8.13.2, 8.7.3] across Gradle builds is not allowed"). AGP 8.13
    // requires Gradle 8.13+, so the wrapper was bumped at the same time.
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    // Required separately from `org.jetbrains.kotlin.android` for any
    // module that uses Jetpack Compose under Kotlin 2.0+ — it replaces
    // the deprecated `kotlinCompilerExtensionVersion` mechanism.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}
