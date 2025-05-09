// settings.gradle.kts
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

rootProject.name = "yappy-platform-build"

// Include all the subprojects
include(
    "bom",
    "util",
 //   "yappy-platform",
    "yappy-test-resources",
    "yappy-consul-config",
    "yappy-models",
    "yappy-models:pipeline-config-models",
    "yappy-models:schema-registry-models",
    "yappy-models:protobuf-models"
)
