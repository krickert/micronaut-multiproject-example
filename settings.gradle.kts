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

rootProject.name = "my-pipeline-system"

// Include all the subprojects
include(
    "bom",
    "protobuf-models",
    "pipeline-service-core",
    "pipeline-service-test-utils",
    "pipeline-service-test-utils:micronaut-kafka-registry",
    "pipeline-service-test-utils:micronaut-test-consul-container",
    "pipeline-service-test-utils:pipeline-test-platform",
    "pipeline-instance-A",
    "pipeline-examples",
    "pipeline-examples:pipeline-echo-service",
    "util"
)
