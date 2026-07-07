import com.google.protobuf.gradle.id

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.protobuf")
    // This module now ships Compose UI (the link screen + the pairing/chat
    // gate `SignalApp`, mirroring the gmessages module) so it needs the Compose
    // Compiler plugin. The Compose runtime/ui artifacts come transitively via
    // api(project(":core")) → the dpad-messenger UI library.
    id("org.jetbrains.kotlin.plugin.compose")
    // KAPT for the ObjectBox annotation processor (generates MyObjectBox +
    // the per-entity Cursor/`_` metadata classes). ObjectBox has no KSP
    // processor yet. Under Kotlin 2.1 this runs in K1-kapt compatibility mode;
    // the "falling back to 1.9" kapt warning is expected and harmless.
    id("org.jetbrains.kotlin.kapt")
}

// ObjectBox database (message store persistence). Applied here — after the
// Android + Kotlin + kapt plugins above — as required by ObjectBox. The plugin
// auto-adds the ObjectBox runtime (objectbox-android + objectbox-kotlin) and the
// kapt processor to this module, so no explicit objectbox dependencies are
// needed below. The plugin classpath is declared in the root build.gradle.kts.
apply(plugin = "io.objectbox")

android {
    namespace = "com.offline.dpadmessenger.backend.signal"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        ndk {
            // armeabi-v7a ONLY. The launcher targets a low-end ARM flip phone
            // (TCL Flip 2), where 32-bit runs fine and native 64-bit speed is
            // irrelevant. libsignal's v7a native lib is ~5 MB vs ~74 MB for the
            // (often unstripped) arm64-v8a one, and v7a runs on every ARM device
            // — so a single v7a lib is both smallest and universally compatible.
            // (Re-add arm64-v8a only if a 64-bit-only target appears; then make
            // sure the build env has the NDK so AGP strips the arm64 .so.)
            abiFilters.add("armeabi-v7a")
        }
        // Ship R8 keep rules (protobuf-lite + libsignal) to any app that
        // minifies while depending on this module — without them a release
        // build mangles the provisioning protobufs ("Invalid response from
        // service"). See consumer-rules.pro.
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes.add("META-INF/AL2.0")
        resources.excludes.add("META-INF/LGPL2.1")
        resources.excludes.add("META-INF/INDEX.LIST")
    }

    // Add proto-generated Java sources PER VARIANT. Each variant must see ONLY
    // its own protoc output. Putting both debug/ and release/ on `main` makes a
    // release compile pick up every generated proto class twice ("duplicate
    // class") — debug builds pass only because release/java is empty then, so
    // the bug stayed hidden until the first assembleRelease.
    sourceSets.getByName("debug").java.srcDir("build/generated/source/proto/debug/java")
    sourceSets.getByName("release").java.srcDir("build/generated/source/proto/release/java")
}

// The kotlin-android plugin provides a top-level `kotlin {}` extension
// whose sourceSets DO have a `kotlin` property. Adding the protoc output
// here makes Kotlin compile resolve the generated classes.
kotlin {
    // Same per-variant split as the android sourceSets above — never put both
    // proto output dirs on `main`, or release compiles see duplicate classes.
    sourceSets.getByName("debug").kotlin.srcDir("build/generated/source/proto/debug/java")
    sourceSets.getByName("release").kotlin.srcDir("build/generated/source/proto/release/java")
}

// Precise per-variant dependency: make compileXxxKotlin run after
// generateXxxProto. `whenTaskAdded` fires when each task is registered,
// regardless of whether that's before or after this block evaluates.
// Naming the tasks directly avoids the test-variant glob that caused
// the circular dependency earlier.
tasks.whenTaskAdded {
    when (name) {
        "compileDebugKotlin" -> dependsOn("generateDebugProto")
        "compileReleaseKotlin" -> dependsOn("generateReleaseProto")
        "compileDebugJavaWithJavac" -> dependsOn("generateDebugProto")
        "compileReleaseJavaWithJavac" -> dependsOn("generateReleaseProto")
    }
}

// protoc + Java-lite codegen for Signal's wire-protocol .proto files
// (copied from Signal-Android into src/main/proto/).
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.1"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    api(project(":core"))

    // Signal's official primitives — ECC, IdentityKey, ProvisioningCipher,
    // SignalProtocolStore, SessionCipher, etc. Maven Central artifact owned
    // by Signal Foundation. Verify the latest version at:
    //   https://central.sonatype.com/artifact/org.signal/libsignal-android
    //
    // IMPORTANT: keep this reasonably current. Signal-Server requires the
    // `spqr` (Sparse Post-Quantum Ratchet) device capability for new
    // linked devices, and the corresponding sealed-sender wire format
    // wasn't recognized by libsignal-android 0.46.x — it would error with
    // "protobuf encoding was invalid" inside SealedSessionCipher. The
    // post-0.7x releases include the new envelope parser.
    api("org.signal:libsignal-android:0.86.5")

    // OkHttp for both the provisioning WebSocket and the authenticated
    // chat WebSocket. Signal's clients use a similar low-level approach.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // kotlinx-serialization for our own JSON persistence of the linked
    // account. The Signal wire protocol uses protobuf — generated by the
    // protobuf plugin above from src/main/proto/*.proto.
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Lite runtime for the generated protobuf classes.
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ZXing (pure-Java core) to ENCODE the provisioning QR for the link screen.
    implementation("com.google.zxing:core:3.5.3")

    // Required by libsignal-android.
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}
