pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "kit"
include(":kit")
// Senso's lab (18 Nov 2026): a small app that shows Senso's debug view, for emulator tests.
// Apps take the Kit as kit/kit, so they never see this module.
include(":lab")
