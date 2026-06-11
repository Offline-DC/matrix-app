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
        // librespot-java's latest Maven Central release (1.6.5, Dec 2024) has a
        // playback-breaking bug: Spotify sunset part of the apresolve spclient
        // pool in Aug 2025 and every spclient request 500s (librespot-java
        // #1098). The fix (PR #1097) is only on the `dev` branch, so we consume
        // a JitPack build of the dev tip instead of the Central artifact.
        maven("https://jitpack.io")
    }
}

rootProject.name = "dpad-spotify"

include(":app")
