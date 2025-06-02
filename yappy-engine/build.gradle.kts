
import io.micronaut.testresources.buildtools.KnownModules

plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
    id("io.micronaut.aot") version "4.5.3"
}

group = rootProject.group
version = rootProject.version
application {
    mainClass = "com.krickert.yappy.engine.YappyEngineApplication"
}

graalvmNative.toolchainDetection = false

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search..**", "com.krickert.testcontainers.consul.*")
    }
    testResources {
        // Disable test resources when:
        // 1. Running with -PdisableTestResources=true
        // 2. Environment variable DISABLE_TEST_RESOURCES=true
        // 3. System property disable.test.resources=true
        val shouldDisable = project.hasProperty("disableTestResources") ||
                           System.getenv("DISABLE_TEST_RESOURCES") == "true" ||
                           System.getProperty("disable.test.resources") == "true"
        
        enabled.set(!shouldDisable)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(false)
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}

dependencies {
    // Apply BOM/platform dependencies
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Project dependencies
    api(project(":yappy-consul-config"))
    implementation(project(":yappy-models:protobuf-models"))

    // Test resources
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testImplementation(project(":yappy-test-resources:moto-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    testImplementation(project(":yappy-modules:echo"))
    testImplementation(project(":yappy-modules:chunker"))

    // Annotation processors
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor(mn.micronaut.json.schema.processor)
    annotationProcessor(mn.micronaut.openapi)
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(libs.bundles.micronaut.annotation.processors)

    // CompileOnly dependencies
    compileOnly(mn.lombok)
    compileOnly(mn.micronaut.openapi.annotations)

    // Runtime dependencies
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    runtimeOnly("io.micronaut.discovery:micronaut-discovery-client")

    // Implementation dependencies - Core Micronaut
    implementation(mn.micronaut.context)
    implementation(mn.micronaut.discovery.client)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.jackson.databind)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.retry)
    implementation(mn.micronaut.json.schema.annotations)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.reactor)
    implementation(mn.micronaut.reactor.http.client)
    implementation(mn.micronaut.views.thymeleaf)
    implementation(mn.micronaut.kafka) {
        exclude("org.testcontainers", "testcontainers-kafka")
    }

    // Implementation dependencies - gRPC
    implementation(mn.micronaut.grpc.annotation)
    implementation(mn.micronaut.grpc.server.runtime)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.protobuff.support)
    implementation(mn.grpc.services)
    implementation(mn.grpc.protobuf)
    implementation("io.grpc:grpc-core:1.72.0")
    implementation(mn.protobuf.java.util)

    // Implementation dependencies - Micrometer
    implementation(mn.micrometer.context.propagation)
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.observation)
    implementation(mn.micronaut.micrometer.registry.jmx)
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus:5.5.0")

    // Other implementation dependencies
    implementation(mn.javax.annotation.api)
    implementation(mn.netty.common)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")
    implementation("info.picocli:picocli:4.7.6")

    // AWS Dependencies
    api(mn.micronaut.aws.sdk.v2)
    api(libs.amazon.glue) {
        exclude(group = "com.squareup.wire")
    }
    api(libs.amazon.msk.iam)
    api(libs.amazon.connection.client)

    // Development only dependencies
    developmentOnly(mn.micronaut.control.panel.management)
    developmentOnly(mn.micronaut.control.panel.ui)

    // Test dependencies
    testImplementation(mn.assertj.core)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.engine)
    testImplementation(mn.junit.jupiter.params)
    testImplementation(mn.mockito.core)
    testImplementation(mn.mockito.junit.jupiter)
    testImplementation(mn.testcontainers.core)
    testImplementation(mn.reactor.test)
    testImplementation(mn.micronaut.http.client.core)
    testImplementation(mn.micronaut.grpc.client.runtime)
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("io.grpc:grpc-testing:1.72.0")
}

// Custom task for running in DEV mode without test resources
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Run the application in DEV mode without test resources"
    
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.krickert.yappy.engine.YappyEngineApplication")
    
    // Set system properties
    systemProperty("micronaut.environments", "dev-apicurio")
    systemProperty("micronaut.test.resources.enabled", "false")
    systemProperty("disable.test.resources", "true")
    
    // Set environment variables
    environment("MICRONAUT_ENVIRONMENTS", "dev-apicurio")
    environment("DISABLE_TEST_RESOURCES", "true")
    environment("KAFKA_BOOTSTRAP_SERVERS", "localhost:9094")
}

// Modify the standard run task to respect test resources disable flag
tasks.named<JavaExec>("run") {
    doFirst {
        if (System.getenv("DISABLE_TEST_RESOURCES") == "true" || 
            System.getProperty("disable.test.resources") == "true") {
            environment("DISABLE_TEST_RESOURCES", "true")
            systemProperty("disable.test.resources", "true")
        }
    }
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Consul Configuration Service")
                description.set("Centralized configuration service using Consul KV store")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}