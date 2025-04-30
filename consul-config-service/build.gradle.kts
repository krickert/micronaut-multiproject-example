// File: consul-config-service/build.gradle.kts
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.micronaut.library)
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
    processing {
        incremental(true)
        annotations("com.krickert.search.config.consul.*")
    }
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))
    // https://mvnrepository.com/artifact/io.micronaut.reactor/micronaut-reactor
    implementation(mn.micronaut.reactor)
    // Micronaut dependencies using mn catalog
    annotationProcessor(libs.bundles.micronaut.annotation.processors)

    // OpenAPI dependencies
    annotationProcessor(mn.micronaut.openapi.common)
    // https://mvnrepository.com/artifact/io.micronaut.openapi/micronaut-openapi
    runtimeOnly(mn.micronaut.openapi.common)
    // https://mvnrepository.com/artifact/io.micronaut.openapi/micronaut-openapi-annotations
    implementation(mn.micronaut.openapi.annotations)

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

    // Implementation dependencies
    api(libs.slf4j.api)
    api(libs.logback.classic)
    compileOnly(mn.lombok)
    implementation(mn.snakeyaml)

    // Project dependencies
    api(project(":util"))
    testAnnotationProcessor(mn.micronaut.inject.java)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.micronaut.test.junit5)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testImplementation(mn.junit.jupiter.engine)
    // Testing dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testAnnotationProcessor(mn.micronaut.inject.java)
    // Testcontainers dependencies
    testImplementation(mn.testcontainers.consul)
    testImplementation(mn.testcontainers.consul)
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:junit-jupiter")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")
    // https://mvnrepository.com/artifact/com.ecwid.consul/consul-api
    implementation("com.ecwid.consul:consul-api:1.4.5")

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
