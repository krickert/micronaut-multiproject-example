pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("mn") {
            from("io.micronaut.platform:micronaut-platform:4.8.2")
        }
    }
}

rootProject.name="yappy-models"

// Include subprojects
include(
    "pipeline-config-models",
    "pipeline-config-models-test-utils",
    "protobuf-models",
    "protobuf-models-test-data-resources"
)

// Include project dependencies
includeBuild("..") {
    dependencySubstitution {
        // Add any dependencies this project needs from other subprojects
    }
}