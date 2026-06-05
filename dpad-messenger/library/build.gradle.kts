plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Required under Kotlin 2.0+ — replaces composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose")
}

// Group/version exposed so the sibling dpad-messenger-backend repo's Gradle
// composite build can substitute `com.offline.dpadmessenger:library` with
// this in-tree project.
group = "com.offline.dpadmessenger"
version = "0.2.0"

android {
    namespace = "com.offline.dpadmessenger"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        compose = true
    }
    // kotlinCompilerExtensionVersion removed under Kotlin 2.0+; the
    // Compose Compiler now tracks the Kotlin version via the
    // `org.jetbrains.kotlin.plugin.compose` plugin applied above.

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.01")
    api(composeBom)

    // Core
    api("androidx.core:core-ktx:1.12.0")
    api("androidx.activity:activity-compose:1.8.2")
    api("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    api("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Compose
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-tooling-preview")
    api("androidx.compose.foundation:foundation")
    api("androidx.compose.material3:material3:1.2.0")
    api("androidx.compose.material:material-icons-extended")

    // TV foundation for DPAD-aware lazy lists. Works fine on phones too.
    api("androidx.tv:tv-foundation:1.0.0-alpha10")
    api("androidx.tv:tv-material:1.0.0-alpha10")

    // Navigation
    api("androidx.navigation:navigation-compose:2.7.7")

    // JSON for mock data
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugApi("androidx.compose.ui:ui-tooling")
}
