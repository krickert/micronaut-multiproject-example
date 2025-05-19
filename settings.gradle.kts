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
    "yappy-test-resources",
    "yappy-test-resources:consul-test-resource",
    "yappy-test-resources:apicurio-test-resource",
    "yappy-test-resources:moto-test-resource",
    "yappy-test-resources:apache-kafka-test-resource",
    "yappy-consul-config",
    "yappy-models",
    "yappy-models:pipeline-config-models",
    "yappy-models:pipeline-config-models-test-utils",
    "yappy-models:protobuf-models",
    "yappy-schema-registry",
    "yappy-modules:echo",
    "yappy-modules:chunker",
    "yappy-engine"
)
