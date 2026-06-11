plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.offline.dpadspotify"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.offline.dpadspotify"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // What cargoNdk below builds. Add x86_64 if you want emulator runs.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

// ---------------------------------------------------------------------------
// Rust core (../rust → src/main/jniLibs/<abi>/libdpadspotify.so)
//
// Build it with:   ./gradlew :app:cargoNdk
// Prereqs:         rustup target add aarch64-linux-android armv7-linux-androideabi
//                  cargo install cargo-ndk      (and an NDK via Android Studio)
//
// Not wired into preBuild on purpose — cargo isn't on everyone's PATH and a
// stale .so is fine for UI iteration. preBuild just warns when it's missing.
// ---------------------------------------------------------------------------
tasks.register<Exec>("cargoNdk") {
    workingDir = rootProject.file("rust")
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-o", project.file("src/main/jniLibs").absolutePath,
        "build", "--release",
    )
}

tasks.named("preBuild") {
    doFirst {
        if (!project.file("src/main/jniLibs").exists()) {
            logger.warn(
                "WARNING: app/src/main/jniLibs is missing — the app will crash at " +
                    "startup without libdpadspotify.so. Run ./gradlew :app:cargoNdk first."
            )
        }
    }
}

dependencies {
    // Web API search + native event JSON parsing.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // ---- UI (mirrors dpad-messenger/library) ----
    val composeBom = platform("androidx.compose:compose-bom:2026.01.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    // AppCompatActivity sidesteps the auto-enableEdgeToEdge() that newer
    // androidx.activity.ComponentActivity runs in onCreate() — without it,
    // the TCL Flip 2 (and other small-screen Android 11 devices) draw a
    // black nav-bar area below the content.
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
