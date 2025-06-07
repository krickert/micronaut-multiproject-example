plugins {
    id("java-library")
    id("io.micronaut.test-resources") version "4.5.3"
}

group = rootProject.group
version = rootProject.version

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral()
}


dependencies {
    // Test resources dependencies
    "testResourcesImplementation"("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    "testResourcesImplementation"("org.testcontainers:testcontainers")

    // Test resources are now in separate modules

    // Use BOM for version management
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Annotation processors
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")

    // Test dependencies
    testImplementation("io.micronaut:micronaut-inject-java")
    testImplementation("io.micronaut.test:micronaut-test-junit5")

    // Logging
    testImplementation(libs.logback.classic)

    // JUnit
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation("org.junit.jupiter:junit-jupiter-params")

    // Assertions
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")

    // Testcontainers
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:consul")

    // HTTP client for testing
    testImplementation("io.micronaut:micronaut-http-client")

    // JSON processing
    testImplementation(libs.jackson.databind)

    // Consul client
    testImplementation("io.micronaut.discovery:micronaut-discovery-client")
    testImplementation("com.orbitz.consul:consul-client:1.5.3")

    // Kafka client
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("io.micronaut.kafka:micronaut-kafka")

    // Apicurio Registry
    testImplementation(libs.apicurio.serde)

    // AWS Dependencies for test resources
    testImplementation("io.micronaut.aws:micronaut-aws-sdk-v2")
    testImplementation(libs.amazon.glue) {
        exclude(group = "com.squareup.wire")
    }
    testImplementation(libs.amazon.msk.iam)
    testImplementation(libs.amazon.connection.client)

    // YAPPY dependencies for configuration management
    testImplementation(project(":yappy-consul-config"))
    testImplementation(project(":yappy-models:pipeline-config-models"))
    testImplementation(project(":yappy-models:pipeline-config-models-test-utils"))
    testImplementation(project(":yappy-models:protobuf-models"))
    
    // gRPC dependencies
    testImplementation("io.grpc:grpc-stub")
    testImplementation("io.grpc:grpc-protobuf")
    testImplementation("io.grpc:grpc-netty-shaded")
    testImplementation("io.micronaut.grpc:micronaut-grpc-runtime")

    // Reactive support
    testImplementation("io.projectreactor:reactor-core")

    // YAPPY test resources - following the pattern from yappy-engine
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testImplementation(project(":yappy-test-resources:moto-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
}

micronaut {
    testResources {
        enabled = true
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
