pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("io.micronaut.platform.catalog") version "4.5.3"
}

rootProject.name = "ChunkerApplication"

// Include required projects
includeBuild("../..") {
    dependencySubstitution {
        substitute(module("com.krickert.yappy:bom")).using(project(":bom"))
        substitute(module("com.krickert.yappy:protobuf-models")).using(project(":yappy-models:protobuf-models"))
    }
}
