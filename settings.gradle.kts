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
// Build order:
// 1. Core dependencies and test resources
// 2. Consul config
// 3. Engine
// 4. Modules
// 5. Containers
// 6. Integration tests

include(
    // Core dependencies
    "bom",
    "util",
    
    // Test resources (needed by consul-config and engine)
    "yappy-test-resources",
    "yappy-test-resources:consul-test-resource",
    "yappy-test-resources:apicurio-test-resource",
    "yappy-test-resources:moto-test-resource",
    "yappy-test-resources:apache-kafka-test-resource",
    "yappy-test-resources:opensearch3-test-resource",
    
    // Models
    "yappy-models",
    "yappy-models:pipeline-config-models",
    "yappy-models:pipeline-config-models-test-utils",
    "yappy-models:protobuf-models",
    "yappy-models:protobuf-models-test-data-resources",
    
    // Consul config
    "yappy-consul-config",
    
    // Engine (no longer depends on modules)
    "yappy-engine"
    
    // TODO: Add these back after engine is updated
    // "yappy-modules:echo",
    // "yappy-modules:chunker",
    // "yappy-modules:tika-parser",
    // "yappy-modules:embedder",
    // "yappy-modules:s3-connector",
    // "yappy-modules:opensearch-sink",
    // "yappy-modules:yappy-connector-test-server"
    
    // TODO: Add containers after modules are ready
    // "yappy-containers",
    
    // TODO: Add integration tests last
    // "yappy-integration-test"
)
