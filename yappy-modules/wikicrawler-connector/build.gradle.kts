// Import for KnownModules
import io.micronaut.testresources.buildtools.KnownModules

plugins {
    id("io.micronaut.minimal.application") version "4.5.3" // From user baseline
    id("io.micronaut.test-resources") version "4.5.3"   // From user baseline
    // Note: The user's baseline did not include `java-library` or `com.gradleup.shadow` from my previous state.
    // I am proceeding with the user's provided plugin block.
}

group = "com.krickert.yappy.modules.wikicrawlerconnector"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain { // ADDED toolchain configuration
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.toVersion("21") // Keep existing from user baseline
    targetCompatibility = JavaVersion.toVersion("21") // Keep existing from user baseline
}

micronaut {
    testRuntime("junit5") // From user baseline
    processing { // From user baseline
        incremental(true)
        annotations("com.krickert.yappy.wikicrawler.*")
    }
    testResources { // ADDED testResources block
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
        // Using fully qualified name for KnownModules as per instruction
        knownModules.add(io.micronaut.testresources.buildtools.KnownModules.KAFKA) 
    }
    // runtime.set(io.micronaut.gradle.MicronautRuntime.NONE) // This was in my original, but not user's. Omitting for now.
}

dependencies {
    // User-provided dependencies - ensure these are exactly as they gave
    annotationProcessor("io.micronaut.openapi:micronaut-openapi") // User provided
    implementation("io.micronaut.openapi:micronaut-openapi-annotations") // User provided

    // Assuming mn. and libs. resolve correctly from the project's version catalog
    annotationProcessor(mn.micronaut.inject.java) 
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.grpc.runtime)

    implementation(project(":yappy-models:protobuf-models"))
    implementation("info.bliki.wiki:bliki-core:3.1.0") 
    implementation(mn.micronaut.kafka)
    implementation(libs.apicurio.serde) 
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(mn.mockito.core)
    testImplementation(mn.assertj.core)
    testImplementation("com.github.tomakehurst:wiremock-jre8-standalone:2.35.0")

    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))

    implementation("io.micronaut.reactor:micronaut-reactor") // User provided
    implementation("io.micronaut.reactor:micronaut-reactor-http-client") // User provided
    runtimeOnly("io.micronaut.openapi:micronaut-openapi:6.15.0") // User provided
}

// ADDED sourceSets block
sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

application { // From user baseline
    mainClass = "com.krickert.yappy.wikicrawler.WikiCrawlerApplication"
}

// Note: The shadowJar task configuration from my original file is not in the user's baseline.
// If shading is needed, it would have to be re-added. For now, sticking to user's baseline + specified additions.
