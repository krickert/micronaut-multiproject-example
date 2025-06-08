plugins {
    id("io.micronaut.library")
    id("io.micronaut.test-resources")
}

repositories {
    mavenCentral()
}

dependencies {
    // Annotation processors
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.inject.java)
    
    // Core dependencies
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    implementation(mn.micronaut.jackson.databind)
    implementation(mn.micronaut.validation)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.projectreactor:reactor-core")
    
    // Project dependencies
    api(project(":yappy-models:protobuf-models"))
    api(project(":yappy-models:pipeline-config-models"))
    
    // Utility dependencies
    implementation(libs.guava)
    implementation(libs.commons.lang3)
    compileOnly(mn.lombok)
    
    // Logging
    implementation(libs.slf4j.api)
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.bundles.testing.jvm)
    testImplementation(mn.assertj.core)
    testImplementation(mn.mockito.core)
    testImplementation("io.projectreactor:reactor-test")
    
    // Test resources support
    testImplementation("io.micronaut.testresources:micronaut-test-resources-client")
    
    // Test containers for integration tests
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
    
    // Additional test utilities
    testImplementation("org.awaitility:awaitility:4.2.0")
    
    // Test runtime dependencies
    testRuntimeOnly(libs.logback.classic)
    
    // Kafka support for tests
    testImplementation(mn.micronaut.kafka)
    testImplementation("org.apache.kafka:kafka-clients")
    
    // Project test resources - handle both root project and standalone builds
    if (rootProject.name == "yappy-platform-build") {
        // Building from root project
        testImplementation(project(":yappy-test-resources:consul-test-resource"))
        testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    } else {
        // Building from yappy-orchestrator directory
        testImplementation("com.krickert.search:consul-test-resource")
        testImplementation("com.krickert.search:apache-kafka-test-resource")
        testResourcesImplementation("com.krickert.search:consul-test-resource")
        testResourcesImplementation("com.krickert.search:apache-kafka-test-resource")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    processing {
        incremental(true)
        annotations("com.krickert.search.engine.core.*")
    }
    testResources {
        enabled = true
        sharedServer = true
    }
}