plugins {
    id("io.micronaut.library") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.kafka"

repositories {
    mavenCentral()
}

dependencies {
    // Micronaut core
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.micronaut.serde.processor)
    
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.jackson.databind)
    
    // Kafka
    implementation(mn.kafka.clients) // Just the Kafka admin client
    
    // Consul (optional - only if using ConsulKafkaSlotManager)
    implementation("org.kiwiproject:consul-client:1.5.1")
    
    // Reactor for reactive programming
    implementation("io.projectreactor:reactor-core")
    
    // Configuration
    implementation(mn.micronaut.management)
    
    // Logging
    implementation("org.slf4j:slf4j-api")
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.awaitility:awaitility:4.2.1")
    testImplementation(mn.reactor.test)
    testImplementation(mn.assertj.core)
    
    // Test resources for integration tests
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    
    // Micronaut Consul support for tests
    testImplementation("io.micronaut.discovery:micronaut-discovery-client")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    runtime("none") // This is a library, not an application
    testRuntime("junit5")
    testResources {
        enabled = true
    }
    processing {
        incremental(true)
        annotations("com.krickert.yappy.kafka.*")
    }
}