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
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // librespot-java's dev branch (Login5 auth path) touches java.time;
        // desugar so the prototype isn't silently API-26+ only.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi"
        )
    }

    packaging {
        resources {
            // librespot ships a log4j2.xml (log4j is excluded below — the
            // config file alone breaks packaging) and Apache-style META-INF
            // files that collide across its transitive deps.
            excludes += listOf(
                "log4j2.xml",
                "META-INF/DEPENDENCIES",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    buildTypes {
        release {
            // No minification for the prototype. If this ever gets minified,
            // AndroidSinkOutput is loaded reflectively (PlayerConfiguration
            // outputClass) and MUST be kept, along with com.spotify.** protos
            // and xyz.gianlu.librespot.audio.decoders.**.
            isMinifyEnabled = false
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // ---- librespot ----
    // Dev-branch tip (52a8c24, Nov 2025) via JitPack instead of the 1.6.5
    // Central release; see settings.gradle.kts for why. JitPack rewrites the
    // inter-module groupIds from xyz.gianlu.librespot to the JitPack group,
    // so excludes are declared under both spellings.
    implementation("com.github.librespot-org.librespot-java:librespot-player:52a8c24215") {
        // Desktop javax.sound sink — useless on Android, we vendor an
        // AudioTrack sink instead (AndroidSinkOutput).
        exclude(group = "com.github.librespot-org.librespot-java", module = "librespot-sink")
        exclude(group = "xyz.gianlu.librespot", module = "librespot-sink")
        // log4j doesn't run on Android; we route slf4j to logcat instead.
        exclude(group = "org.apache.logging.log4j")
        exclude(group = "com.lmax", module = "disruptor")
    }
    // slf4j 2.x (librespot dev uses slf4j-api 2.0.16) → logcat provider.
    implementation("uk.uuid.slf4j:slf4j-android:2.0.7-0")
    // Used directly by WebApi.kt (also a librespot transitive).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.11.0")
    // The vendored Java sink/decoder sources use @NotNull annotations.
    compileOnly("org.jetbrains:annotations:24.1.0")

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
