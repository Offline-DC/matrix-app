// Standalone JVM harness for the Signal protocol-store fixes. NOT part of the
// app build (not in ../settings.gradle.kts). It compiles the module's REAL
// source files (synced from ../signal at build time, so it can't drift) against
// the JVM libsignal-client of the same version, with tiny android.* stubs.
//
//   cd dpad-messenger-backend/signal-protocol-harness && ./gradlew -q run
plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
}
repositories { mavenCentral(); google() }
dependencies {
    // Same version as ../signal (libsignal-android 0.86.5 wraps this client).
    implementation("org.signal:libsignal-client:0.86.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
}
kotlin { jvmToolchain(21) }
application { mainClass.set("sig.HarnessKt") }

val signalSrc = file("../signal/src/main/kotlin/com/offline/dpadmessenger/backend/signal")
val synced = layout.buildDirectory.dir("synced/kotlin")

// Real files under test, verbatim.
val syncReal by tasks.registering(Sync::class) {
    from(signalSrc) {
        include("AndroidSignalProtocolStore.kt", "SigNames.kt", "SignalAccount.kt", "SignalDeviceMismatch.kt", "SignalKeys.kt")
    }
    // The pre-fix store from beta d21fa78, kept frozen to reproduce the bug.
    from("frozen")
    into(synced.map { it.dir("real") })
}
// The link-request data classes + the Json config, extracted verbatim from SignalApi.kt.
val extractApiModels by tasks.registering {
    val src = file("$signalSrc/SignalApi.kt")
    val out = synced.map { it.file("api/ApiModels.kt") }
    inputs.file(src); outputs.file(out)
    doLast {
        val s = src.readText()
        val parts = mutableListOf(
            "@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)",
            "package com.offline.dpadmessenger.backend.signal",
            "import kotlinx.serialization.EncodeDefault",
            "import kotlinx.serialization.Serializable",
            "import kotlinx.serialization.json.Json",
        )
        for (name in listOf("ConfirmDeviceRequest", "AccountAttributes", "Capabilities")) {
            val i = s.indexOf("@Serializable\ndata class $name(")
            check(i >= 0) { "$name not found in SignalApi.kt" }
            parts += s.substring(i, s.indexOf("\n)\n", i) + 3)
        }
        val m = Regex("""private val JSON = (Json \{.*?\n        \})""", RegexOption.DOT_MATCHES_ALL).find(s)
            ?: error("JSON config not found in SignalApi.kt")
        parts += "val API_JSON = " + m.groupValues[1]
        out.get().asFile.apply { parentFile.mkdirs(); writeText(parts.joinToString("\n")) }
    }
}
sourceSets.main { kotlin.srcDir(syncReal); kotlin.srcDir(synced.map { it.dir("api") }) }
tasks.named("compileKotlin") { dependsOn(syncReal, extractApiModels) }
