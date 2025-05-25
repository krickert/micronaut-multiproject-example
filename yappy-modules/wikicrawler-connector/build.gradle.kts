import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import io.micronaut.testresources.buildtools.KnownModules

plugins {
    id("io.micronaut.minimal.application") version libs.versions.micronautPlugins.get()
    id("com.gradleup.shadow") version "8.3.6" // Not in libs.versions.toml
    `java-library`
}

group = "com.krickert.yappy.modules.wikicrawlerconnector"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    runtime.set(io.micronaut.gradle.MicronautRuntime.NONE)
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.wikicrawler.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
        knownModules.add(KnownModules.KAFKA)
    }
}

dependencies {
    // Micronaut - using mn notation which should resolve via version catalog
    annotationProcessor(mn.micronaut.inject.java) // maps to libs.micronaut.inject.java
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(libs.micronaut.openapi.visitor) // For OpenAPI spec generation
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.grpc.runtime)
    implementation(libs.micronaut.openapi.ui) // For Swagger UI

    // Yappy Models (Protobufs)
    implementation(project(":yappy-models:protobuf-models"))

    // Bliki Engine (Wikipedia Parsing)
    implementation("info.bliki.wiki:bliki-core:3.1.0") // Not in libs.versions.toml

    // Kafka
    implementation(mn.micronaut.kafka)
    implementation(libs.apicurio.serde) // Using alias from libs.versions.toml (io.apicurio:apicurio-registry-protobuf-serde-kafka)

    // Logging
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    // Lombok (Optional)
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(mn.mockito.core)
    testImplementation(mn.assertj.core)
    testImplementation("com.github.tomakehurst:wiremock-jre8-standalone:2.35.0") // WireMock for HTTP client testing

    // Yappy Test Resources
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
}

tasks.withType<ShadowJar> {
    archiveClassifier.set("shaded")

    relocate("info.bliki", "com.krickert.shaded.bliki")
    relocate("org.xml.sax", "com.krickert.shaded.org.xml.sax")

    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES*")
}

// Ensure compilation happens before shadowJar if shadowJar is a primary artifact
// tasks.named("build") {
// dependsOn(tasks.shadowJar)
// }
