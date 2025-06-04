plugins {
    id("java")
    id("io.micronaut.test-resources") version "4.5.3"
}

group = "com.krickert.yappy.tests"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // Micronaut test resources
    testImplementation("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    testImplementation("io.micronaut.testresources:micronaut-test-resources-client")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    
    // Our projects - using coordinates instead of project dependencies
    testImplementation("com.krickert.search:consul-test-resource:1.0.0-SNAPSHOT")
    testImplementation("com.krickert.search:apache-kafka-test-resource:1.0.0-SNAPSHOT")
    testImplementation("com.krickert.search:apicurio-test-resource:1.0.0-SNAPSHOT")
    
    // Models
    testImplementation("com.krickert.search:protobuf-models:1.0.0-SNAPSHOT")
    testImplementation("com.krickert.search:pipeline-config-models:1.0.0-SNAPSHOT")
    testImplementation("com.krickert.search:yappy-consul-config:1.0.0-SNAPSHOT")
    
    // gRPC client for testing
    testImplementation("io.grpc:grpc-netty-shaded:1.68.1")
    testImplementation("io.grpc:grpc-protobuf:1.68.1")
    testImplementation("io.grpc:grpc-stub:1.68.1")
    testImplementation("io.grpc:grpc-testing:1.68.1")
    
    // Kafka client
    testImplementation("org.apache.kafka:kafka-clients:3.7.0")
    testImplementation("io.confluent:kafka-protobuf-serializer:7.8.0")
    
    // HTTP client
    testImplementation("io.micronaut:micronaut-http-client")
    
    // JSON
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    
    // Logging
    testImplementation("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
}

// Configure test resources
micronaut {
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(120)
        
        additionalModules.add("kafka")
        additionalModules.add("consul")
        additionalModules.add("testcontainers")
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    
    // Increase timeout for container tests
    systemProperty("junit.jupiter.execution.timeout.default", "5m")
    
    // Pass container image info
    systemProperty("engine.tika.image", "localhost:5000/yappy/engine-tika-parser:latest")
    systemProperty("engine.chunker.image", "localhost:5000/yappy/engine-chunker:latest")
    systemProperty("engine.embedder.image", "localhost:5000/yappy/engine-embedder:latest")
}