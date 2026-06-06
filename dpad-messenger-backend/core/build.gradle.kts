plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.offline.dpadmessenger.backend.core"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // The UI library — provides MessageRepository, Message, Room, etc.
    api("com.offline.dpadmessenger:library:0.2.0")

    // EncryptedSharedPreferences for session token persistence.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    api("androidx.core:core-ktx:1.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
