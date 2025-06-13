plugins {
    id("io.micronaut.library")
    alias(libs.plugins.protobuf)
}

dependencies {
    // Yappy dependencies
    implementation(project(":yappy-models:protobuf-models"))
    implementation(project(":yappy-commons"))
    
    // Micronaut dependencies
    implementation("io.micronaut:micronaut-inject")
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime")
    implementation("io.micronaut.grpc:micronaut-grpc-server-runtime")
    
    // gRPC dependencies
    implementation("io.grpc:grpc-netty")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-services") // For health check service
    implementation("javax.annotation:javax.annotation-api")
    
    // Consul client for module registration
    implementation("com.ecwid.consul:consul-api:1.4.5")
    
    // Testing
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.grpc:grpc-testing")
    testImplementation("io.grpc:grpc-inprocess") // For in-process gRPC testing
    testImplementation("org.mockito:mockito-core")
    
    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")
    
    // YAML support
    runtimeOnly("org.yaml:snakeyaml")
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
}