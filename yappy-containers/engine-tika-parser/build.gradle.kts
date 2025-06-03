plugins {
    id("java")
    id("io.micronaut.application")
    id("io.micronaut.aot")
    id("io.micronaut.crac")
    id("com.gradleup.shadow")
}

dependencies {
    // Engine dependency
    implementation("com.krickert.search:yappy-engine")
    
    // Module dependency
    implementation("com.krickert.search.modules:tika-parser")
    
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
    mainClass.set("com.krickert.yappy.engine.YappyEngineApplication")
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    
    // Module-specific configuration
    processing {
        incremental(true)
        annotations("com.krickert.yappy.*", "com.krickert.search.*")
    }
    
    // AOT configuration for this module
    aot {
        optimizeServiceLoading.set(true)
        convertYamlToJava.set(true)
        precomputeOperations.set(true)
        cacheEnvironment.set(true)
        optimizeClassLoading.set(true)
        deduceEnvironment.set(true)
        optimizeNetty.set(true)
        replaceLogbackXml.set(true)
    }
}

// GraalVM native configuration
graalvmNative.toolchainDetection.set(false)

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion.set("21")
}