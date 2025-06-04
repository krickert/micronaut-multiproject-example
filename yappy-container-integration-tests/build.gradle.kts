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
    // Micronaut test resources for infrastructure
    testImplementation("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    testImplementation("io.micronaut.testresources:micronaut-test-resources-client")
    
    // Testcontainers
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    testImplementation("org.testcontainers:junit-jupiter:1.20.4")
    testImplementation("org.testcontainers:kafka:1.20.4")
    testImplementation("org.testcontainers:consul:1.20.4")
    
    // Docker compose support
    testImplementation("org.testcontainers:docker-compose:1.20.4")
    
    // gRPC client for testing
    testImplementation("io.grpc:grpc-netty-shaded:1.68.1")
    testImplementation("io.grpc:grpc-protobuf:1.68.1")
    testImplementation("io.grpc:grpc-stub:1.68.1")
    testImplementation("io.grpc:grpc-testing:1.68.1")
    
    // Kafka client for testing
    testImplementation("org.apache.kafka:kafka-clients:3.7.0")
    testImplementation("io.confluent:kafka-protobuf-serializer:7.8.0")
    
    // Consul client
    testImplementation("com.ecwid.consul:consul-api:1.4.5")
    
    // JSON processing
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    
    // HTTP client for REST APIs
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.4.1")
    
    // JUnit 5
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.assertj:assertj-core:3.27.2")
    testImplementation("org.awaitility:awaitility:4.2.2")
    
    // Logging
    testImplementation("org.slf4j:slf4j-api:2.0.16")
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.15")
    
    // Include protobuf models from main project
    testImplementation(project(":yappy-models:protobuf-models"))
    testImplementation(project(":yappy-models:pipeline-config-models"))
}

// Configure test resources
micronaut {
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(120)
        
        additionalModules.add("kafka")
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
    
    // Pass docker registry location
    systemProperty("docker.registry", System.getProperty("docker.registry", "localhost:5000"))
    systemProperty("docker.namespace", System.getProperty("docker.namespace", "yappy"))
}

// Task to build required containers before running tests
tasks.register("buildTestContainers") {
    group = "build"
    description = "Build containers needed for integration tests"
    
    doLast {
        exec {
            workingDir = file("../yappy-containers")
            commandLine("./build-engine-tika.sh")
        }
        exec {
            workingDir = file("../yappy-containers")
            commandLine("./build-engine-chunker.sh")
        }
        // Add more container builds as needed
    }
}

tasks.test.configure {
    dependsOn("buildTestContainers")
}