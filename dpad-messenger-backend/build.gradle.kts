plugins {
    // Bumped 8.7.3 → 8.13.2 to align with the dumb-down-launcher repo,
    // which composite-includes this backend (and via it, ../dpad-messenger).
    // Composite builds require all included builds to use the SAME AGP
    // version. AGP 8.13 still bundles an R8 that handles Kotlin 2.1
    // metadata (the original reason for the 8.7 bump). Requires Gradle
    // 8.13+, so gradle-wrapper.properties was bumped at the same time.
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    // Bumped from 1.9.22 because libsignal-android 0.86.5 ships Kotlin
    // 2.1.0 metadata that pre-2.0 compilers refuse to read.
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.10" apply false
    // Required separately from `org.jetbrains.kotlin.android` for any
    // module that uses Jetpack Compose under Kotlin 2.0+ — it replaces
    // the deprecated `kotlinCompilerExtensionVersion` mechanism. Apply
    // per-module by id only, no version needed here (it follows the
    // root Kotlin version).
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.offline.dpadmessenger.backend"
    version = "0.1.0-SNAPSHOT"
}
