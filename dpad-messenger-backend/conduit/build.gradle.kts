plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offline.dpadmessenger.backend.conduit"
    compileSdk = 34
    defaultConfig {
        minSdk = 24
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // When you add the Conduit prebuilt .so, drop it under jniLibs/.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    api(project(":core"))
    implementation("androidx.core:core-ktx:1.12.0")
}
