import io.micronaut.testresources.buildtools.KnownModules

plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.test-resources") version "4.5.3"
    id("io.micronaut.library") version "4.5.3"
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
        annotations("com.krickert.search.test.**")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        additionalModules.add(KnownModules.KAFKA)
        clientTimeout.set(60)
        sharedServer.set(true)
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

    // API dependencies - these are exposed to consumers of the library
    api(mn.micronaut.inject)
    api(mn.micronaut.runtime)
    api(libs.slf4j.api)
    api(libs.logback.classic)


    // Test container dependencies
    api("org.testcontainers:testcontainers")
    api("org.testcontainers:junit-jupiter")
    api("org.testcontainers:consul")
    api("org.testcontainers:kafka")
    // https://mvnrepository.com/artifact/io.micronaut.testresources/micronaut-test-resources-testcontainers
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers:2.8.0")
    api("io.micronaut.testresources:micronaut-test-resources-core:2.8.0")

    // Implementation dependencies
    implementation(mn.micronaut.test.junit5)
    implementation(mn.junit.jupiter.api)
    implementation(mn.junit.jupiter.engine)
    implementation("org.awaitility:awaitility:4.3.0")

    // AWS SDK dependencies
    api(mn.micronaut.aws.sdk.v2)

    // AWS Glue Schema Registry dependencies
    api(libs.amazon.glue) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }
    api(libs.amazon.msk.iam)
    api(libs.amazon.connection.client)

    // Apicurio Registry dependencies
    api(libs.apicurio.serde) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }

    // Explicitly include Wire library to ensure consistent version
    api("com.squareup.wire:wire-schema:5.3.1")
    api("com.squareup.wire:wire-runtime:5.3.1")

    // Compile-only dependencies
    compileOnly(mn.lombok)

    // Test dependencies
    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.engine)
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.engine)
    testImplementation(mn.assertj.core)
    testImplementation(mn.hamcrest)
    testImplementation(mn.mockito.junit.jupiter)
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Test Resources")
                description.set("Test resources and utilities for Micronaut applications")

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
