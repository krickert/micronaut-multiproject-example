plugins {
    id("io.micronaut.library")
    id("io.micronaut.test-resources")
}

dependencies {
    // Yappy dependencies
    implementation(project(":yappy-models:pipeline-config-models"))
    implementation(project(":yappy-models:protobuf-models"))
    implementation(project(":yappy-consul-config"))
    implementation(project(":yappy-orchestrator:engine-core"))
    implementation(project(":yappy-orchestrator:kafka-service"))
    testImplementation(project(":yappy-models:protobuf-models-test-data-resources"))
    testImplementation(project(":yappy-test-resources:yappy-engine-test-resource"))
    testImplementation(project(":yappy-test-resources:yappy-chunker-test-resource"))
    testImplementation(project(":yappy-test-resources:yappy-test-module-test-resource"))
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    
    // Micronaut dependencies
    implementation("io.micronaut:micronaut-inject")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.micronaut.grpc:micronaut-grpc-client-runtime")
    
    // Testing dependencies
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.projectreactor:reactor-test")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    
    // Test resources implementation dependencies
    testResourcesImplementation("org.testcontainers:kafka:1.21.1")
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:opensearch3-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-engine-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-chunker-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-test-module-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-tika-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-embedder-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:yappy-echo-test-resource"))
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.*")
    }
    testResources {
        enabled = true
        sharedServer = true
        clientTimeout = 300  // 5 minutes - engine needs time to wait for dependencies
    }
}

configurations.all {
    exclude(group = "io.micronaut.testresources", module = "micronaut-test-resources-kafka")
}

graalvmNative {
    toolchainDetection.set(false)
}

tasks.test {
    useJUnitPlatform()
    
    // Increase timeout for integration tests
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
    
    // Set system properties for test containers
    systemProperty("testcontainers.reuse.enable", "false")
}