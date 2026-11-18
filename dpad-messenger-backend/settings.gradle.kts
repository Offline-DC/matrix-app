pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // matrix-rust-sdk Kotlin bindings ship via Maven Central, but pre-release
        // builds sometimes go to GitHub Packages. If you bump to a snapshot,
        // add that repo here.

        // libsignal: Signal stopped publishing to Maven Central after 0.86.5;
        // newer releases are only on Signal's own repo. Scoped to org.signal so
        // nothing else resolves from it. The launcher (composite build) needs
        // the same entry in ITS settings — it resolves :signal's libsignal dep.
        maven("https://build-artifacts.signal.org/libraries/maven/") {
            content { includeGroup("org.signal") }
        }
    }
}

rootProject.name = "dpad-messenger-backend"

include(":core")
include(":matrix")
include(":conduit")
include(":signal")
include(":gmessages")
// Native SmartTxt backend (migrated from the standalone imessage-app repo).
// Ships its own Compose setup/chat-gate UI against the shared dpad-messenger
// library, exactly like :gmessages. The rustpush `.so` it loads is built by
// scripts/build-rustpush-so.sh from the sibling smarttxt-ffi crate; until then
// it runs in stub/relay mode. See ABSINTHE_REVERSE_ENGINEERING.md.
include(":smarttxt")
include(":app")

// Composite build: the UI library lives in the sibling dpad-messenger repo.
// This lets the backend modules implement com.offline.dpadmessenger:library's
// MessageRepository interface directly, without anyone having to publish
// to Maven first. When you bump version or publish, swap this out for a
// real dependency in matrix/build.gradle.kts.
includeBuild("../dpad-messenger") {
    dependencySubstitution {
        substitute(module("com.offline.dpadmessenger:library"))
            .using(project(":library"))
    }
}
