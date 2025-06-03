plugins {
    id("java")
    id("io.micronaut.application")
    id("io.micronaut.aot")
    id("io.micronaut.crac")
    id("com.gradleup.shadow")
}

dependencies {
    // Module dependency only - this is a standalone service
    implementation("com.krickert.search.modules:test-connector")
    
    // Micronaut dependencies
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime")
    implementation("io.micronaut:micronaut-health")
    implementation("io.micronaut:micronaut-management")
    
    // Runtime dependencies
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
}

application {
    // Test connector has its own main class
    mainClass.set("com.krickert.yappy.modules.connector.test.Application")
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    
    processing {
        incremental(true)
        annotations("com.krickert.yappy.*", "com.krickert.search.*")
    }
    
    // AOT configuration
    aot {
        optimizeServiceLoading.set(true)
        convertYamlToJava.set(true)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
    }
}

// GraalVM native configuration
graalvmNative.toolchainDetection.set(false)

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion.set("21")
}