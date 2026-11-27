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
// Banco (27 Nov 2026): the audit bench, a home screen hosting every family widget for real
include(":banco")
