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

    // Let plain JVM unit tests run without Robolectric: android.util.Log and friends
    // return defaults (0/null) instead of throwing "not mocked". MediaCacheTest needs
    // exactly this much — it exercises real java.io.File behaviour, not Android APIs.
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // The UI library — provides MessageRepository, Message, Room, etc.
    api("com.offline.dpadmessenger:library:0.2.0")

    // EncryptedSharedPreferences for session token persistence.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    api("androidx.core:core-ktx:1.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
}
