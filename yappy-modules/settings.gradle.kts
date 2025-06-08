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

rootProject.name="yappy-modules"

// Include subprojects
include(
    "tika-parser",
    "chunker",
    "echo",
    "embedder",
    "s3-connector",
    "opensearch-sink",
    "yappy-connector-test-server",
    "test-connector",
    "web-crawler-connector",
    "wikicrawler-connector",
    "wikipedia-connector"
)

// Include project dependencies
includeBuild("..") {
    dependencySubstitution {
        substitute(module("com.krickert.search:yappy-test-resources")).using(project(":yappy-test-resources"))
        substitute(module("com.krickert.search:protobuf-models")).using(project(":yappy-models:protobuf-models"))
        substitute(module("com.krickert.search:pipeline-config-models")).using(project(":yappy-models:pipeline-config-models"))
    }
}