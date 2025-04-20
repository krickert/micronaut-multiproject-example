// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    // Apply Micronaut Platform Catalog plugin here for centralized Micronaut dependency management
    // This plugin makes Micronaut's managed dependencies available via the 'mn' accessor
    plugins {
        id("io.micronaut.platform.catalog") version "4.8.1" // Use latest compatible version [1]
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral() // Repositories for project dependencies
    }
    // Enable the version catalog feature
    versionCatalogs {
        // Gradle automatically looks for libs.versions.toml in gradle/ [2]
        // The Micronaut catalog plugin provides the 'mn' catalog [3]
    }
}

rootProject.name = "my-pipeline-system"

// Include all the subprojects
include(
    "bom",
    "protobuf-models",
    "pipeline-service-core",
    "pipeline-service-test-utils",
    "pipeline-instance-A",
    "util"
)
// Add more pipeline instances or other modules as needed
// include("pipeline-instance-B")