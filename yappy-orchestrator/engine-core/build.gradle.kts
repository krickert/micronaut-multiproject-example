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
    
    // Consul client
    implementation("com.ecwid.consul:consul-api:1.4.5")
    
    // gRPC dependencies
    implementation("io.micronaut.grpc:micronaut-grpc-client-runtime")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-protobuf")
    implementation("javax.annotation:javax.annotation-api")
    implementation("com.google.protobuf:protobuf-java-util:3.24.4")
    
    // Project dependencies
    api(project(":yappy-models:protobuf-models"))
    api(project(":yappy-models:pipeline-config-models"))
    implementation(project(":yappy-consul-config"))
    
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
    
    // gRPC support for module integration tests
    testImplementation("io.micronaut.grpc:micronaut-grpc-client-runtime")
    testImplementation("io.grpc:grpc-stub")
    testImplementation("io.grpc:grpc-protobuf")
    testImplementation("javax.annotation:javax.annotation-api")
    
    // Consul client for service discovery tests
    testImplementation("com.ecwid.consul:consul-api:1.4.5")
    
    // Project test resources - handle both root project and standalone builds
    if (rootProject.name == "yappy-platform-build") {
        // Building from root project
        testImplementation(project(":yappy-test-resources:consul-test-resource"))
        testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
        testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
        testImplementation(project(":yappy-test-resources:moto-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
        testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    } else {
        // Building from yappy-orchestrator directory
        testImplementation("com.krickert.search:consul-test-resource")
        testImplementation("com.krickert.search:apache-kafka-test-resource")
        testImplementation("com.krickert.search:apicurio-test-resource")
        testImplementation("com.krickert.search:moto-test-resource")
        testResourcesImplementation("com.krickert.search:consul-test-resource")
        testResourcesImplementation("com.krickert.search:apache-kafka-test-resource")
        testResourcesImplementation("com.krickert.search:apicurio-test-resource")
        testResourcesImplementation("com.krickert.search:moto-test-resource")
    }
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Disable CDS to prevent JVM crashes
    jvmArgs("-XX:+UnlockDiagnosticVMOptions", "-XX:-UseSharedSpaces", "-Xshare:off")
}

micronaut {
    processing {
        incremental(true)
        annotations("com.krickert.search.engine.core.*")
    }
    testResources {
        enabled = true
        sharedServer = true
        clientTimeout = 60
    }
}