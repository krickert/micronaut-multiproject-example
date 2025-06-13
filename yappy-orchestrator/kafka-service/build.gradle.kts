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
    implementation(mn.micronaut.serde.jackson)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.projectreactor:reactor-core")
    
    // Kafka dependencies
    implementation(mn.micronaut.kafka)
    implementation("org.apache.kafka:kafka-clients")
    implementation("org.apache.kafka:kafka-streams")
    
    // Project dependencies
    api(project(":yappy-models:protobuf-models"))
    api(project(":yappy-models:pipeline-config-models"))
    implementation(project(":yappy-commons"))
    
    // Utility dependencies
    implementation(libs.guava)
    implementation(libs.commons.lang3)
    compileOnly(mn.lombok)
    
    // Logging
    implementation(mn.logback.classic)
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.bundles.testing.jvm)
    testImplementation(mn.assertj.core)
    testImplementation(mn.mockito.core)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    
    // Test resources support
    testImplementation("io.micronaut.testresources:micronaut-test-resources-client")
    
    // Test containers for Kafka integration tests
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
    
    // Additional test utilities
    testImplementation("org.awaitility:awaitility:4.2.0")
    

    // Project test resources
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    
    // Pipeline configuration test utilities
    testImplementation(project(":yappy-models:pipeline-config-models-test-utils"))
    
    // Test resources implementation dependencies
    testResourcesImplementation("org.testcontainers:kafka:1.21.1")
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    // Implementation dependencies - Micrometer
    implementation(mn.micrometer.context.propagation)
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.observation)
    implementation(mn.micronaut.micrometer.registry.jmx)
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus:5.5.0")
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.9")
    api(mn.micronaut.aws.sdk.v2)
    api(libs.amazon.glue) {
        exclude(group = "com.squareup.wire")
    }
    api(libs.amazon.msk.iam)
    api(libs.amazon.connection.client)

    testImplementation(mn.mockito.junit.jupiter)
    //TODO: bring slot manager as a sister project in kafka-service later
    // implementation(project(":yappy-kafka-slot-manager")) // Removed temporarily to fix tests
    implementation(project(":yappy-consul-config"))



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
        annotations("com.krickert.search.orchestrator.kafka.*")
    }
    testResources {
        enabled = true
        sharedServer = true
        clientTimeout = 60
    }
}