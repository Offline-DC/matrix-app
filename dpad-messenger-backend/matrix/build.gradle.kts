plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offline.dpadmessenger.backend.matrix"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        // matrix-rust-sdk uses 16KB-aligned native libs and excludes some
        // legacy ABIs. Stick to what the device has.
        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // matrix-rust-sdk ships uniffi-generated JNI libs; avoid duplicate
        // META-INF collisions if other deps pull in the same.
        resources.excludes.add("META-INF/AL2.0")
        resources.excludes.add("META-INF/LGPL2.1")
    }
}

dependencies {
    api(project(":core"))

    // Matrix Rust SDK Kotlin bindings (UniFFI-generated). Same library
    // Element X Android uses. Version is the most recent at the time of
    // writing — bump as needed. The artifact is published to Maven Central.
    //
    // If you need a snapshot or older version, the publish history is at:
    //   https://central.sonatype.com/artifact/org.matrix.rustcomponents/sdk-android
    api("org.matrix.rustcomponents:sdk-android:0.2.74")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.core:core-ktx:1.12.0")
}
