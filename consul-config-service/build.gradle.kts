import io.micronaut.testresources.buildtools.KnownModules

// File: consul-config-service/build.gradle.kts
plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.test-resources") version "4.5.3"
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.aot") version "4.5.3"
}

group = rootProject.group
version = rootProject.version

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    version("4.8.2")
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.config.consul.*")
    }
    testResources {
        enabled.set(true) // true by default
        inferClasspath.set(true) // true by default
        additionalModules.add(KnownModules.HASHICORP_CONSUL)
        clientTimeout.set(60) // in seconds, maximum time to wait for resources to be available, 60s by default
        sharedServer.set(true) // false by default
    }
    aot {
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

    // Annotation processors
    annotationProcessor(libs.bundles.micronaut.annotation.processors)
    annotationProcessor(mn.micronaut.openapi.common)

    // API dependencies - these are exposed to consumers of the library
    api(mn.micronaut.inject)
    api(mn.micronaut.serde.api)
    api(mn.micronaut.serde.jackson)
    api(mn.micronaut.jackson.databind)
    api(mn.micronaut.runtime)
    api(mn.micronaut.validation)
    api(mn.micronaut.management)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.discovery.client)
    api(mn.micronaut.discovery.core)
    api(libs.slf4j.api)
    api(libs.logback.classic)

    // Implementation dependencies
    implementation(mn.micronaut.reactor)
    implementation(mn.micronaut.reactor.http.client)
    implementation(mn.micronaut.openapi.annotations)
    implementation(mn.micronaut.kafka)
    implementation(mn.micronaut.jmx)
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.registry.jmx)
    implementation(mn.micronaut.views.fieldset)
    implementation(mn.micronaut.views.thymeleaf)
    // Compile-only dependencies
    compileOnly(mn.lombok)

    // Runtime dependencies
    runtimeOnly(mn.micronaut.openapi.common)
    runtimeOnly(mn.snakeyaml)

    // Test dependencies
    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.engine)
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(mn.testcontainers.kafka)
    testImplementation(mn.reactor.test)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation("org.junit.platform:junit-platform-suite-engine")
    testImplementation("org.testcontainers:junit-jupiter")
    // https://mvnrepository.com/artifact/com.fasterxml.jackson.dataformat/jackson-dataformat-yaml
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.19.0")
    // https://mvnrepository.com/artifact/org.kiwiproject/consul-client
    implementation("org.kiwiproject:consul-client:1.5.1")

    // https://mvnrepository.com/artifact/org.testcontainers/consul
    testResourcesImplementation("org.testcontainers:consul:1.21.0")
    // https://mvnrepository.com/artifact/org.awaitility/awaitility
    testImplementation("org.awaitility:awaitility:4.3.0")
    // https://mvnrepository.com/artifact/com.networknt/json-schema-validator
    implementation("com.networknt:json-schema-validator:1.5.6")
    testImplementation(mn.hamcrest)

    // Add protobuf-models dependency for gRPC service
    implementation(project(":protobuf-models"))
    // Add gRPC dependencies
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.grpc.server.runtime)
    testImplementation(mn.micronaut.grpc.client.runtime)
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

application {
    mainClass = "com.krickert.search.config.consul.Application"
}

graalvmNative.toolchainDetection = false

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}

tasks.withType<io.micronaut.gradle.testresources.StartTestResourcesService>().configureEach {
    useClassDataSharing.set(false)
}