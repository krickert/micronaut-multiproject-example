import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

group = "com.krickert.yappy.modules.wikicrawlerconnector"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.wikicrawler.*")
    }
}

dependencies {
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    implementation("io.micronaut.openapi:micronaut-openapi-annotations")
    // Micronaut - using mn notation which should resolve via version catalog
    annotationProcessor(mn.micronaut.inject.java) // maps to libs.micronaut.inject.java
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.grpc.runtime)

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

    // Reactor
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    // https://mvnrepository.com/artifact/io.micronaut.openapi/micronaut-openapi
    runtimeOnly("io.micronaut.openapi:micronaut-openapi:6.15.0")

}

application {
    mainClass = "com.krickert.yappy.wikicrawler.WikiCrawlerApplication"
}

sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    archiveClassifier.set("shaded")

    relocate("info.bliki", "com.krickert.shaded.info.bliki")
    relocate("org.xml.sax", "com.krickert.shaded.org.xml.sax")

    exclude("META-INF/LICENSE*")
    exclude("META-INF/NOTICE*")
    exclude("META-INF/DEPENDENCIES*")
}
