plugins {
    id("io.micronaut.library")
    id("io.micronaut.test-resources")
}

dependencies {
    // Yappy dependencies
    implementation(project(":yappy-models:pipeline-config-models"))
    implementation(project(":yappy-models:protobuf-models"))
    implementation(project(":yappy-consul-config"))
    testImplementation(project(":yappy-test-resources:yappy-engine-test-resource"))
    
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
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.*")
    }
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
    
    // Ensure Docker images are built before running integration tests
    dependsOn(
        ":yappy-orchestrator:dockerBuild",
        ":containers:chunker:dockerBuild",
        ":yappy-modules:test-module:dockerBuild"
    )
}