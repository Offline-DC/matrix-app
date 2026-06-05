plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    // Required under Kotlin 2.0+ — replaces composeOptions.kotlinCompilerExtensionVersion.
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offline.dpadmessenger.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.offline.dpadmessenger.demo"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
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
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(project(":library"))
    // AppCompatActivity sidesteps the auto-enableEdgeToEdge() that newer
    // androidx.activity.ComponentActivity runs in onCreate() — without it,
    // the TCL Flip 2 (and other small-screen Android 11 devices) draw a
    // black nav-bar area below the content.
    implementation("androidx.appcompat:appcompat:1.6.1")
}
