pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "libreforge"

include(":core")
include(":core:common")
include(":loader")
include(":gradle-plugin")
