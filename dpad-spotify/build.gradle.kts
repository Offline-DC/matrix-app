// Root build file. Plugin versions kept in lockstep with the sibling
// dpad-messenger build (and transitively dumb-down-launcher) so this repo can
// be composite-included later without the "multiple AGP versions" error.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    // Required separately from `org.jetbrains.kotlin.android` for any module
    // that uses Jetpack Compose under Kotlin 2.0+.
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}
