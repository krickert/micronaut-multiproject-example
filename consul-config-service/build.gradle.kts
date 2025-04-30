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
    implementation("io.micronaut.reactor:micronaut-reactor:3.7.0")
    // Micronaut dependencies using mn catalog
    annotationProcessor(libs.bundles.micronaut.annotation.processors)

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
// https://mvnrepository.com/artifact/com.ecwid.consul/consul-api
    implementation("com.ecwid.consul:consul-api:1.4.5")

    // Implementation dependencies
    api(libs.slf4j.api)
    api(libs.logback.classic)
    compileOnly(mn.lombok)
    runtimeOnly("org.yaml:snakeyaml")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.2")

    // Project dependencies
    api(project(":util"))
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    // Testing dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    //testImplementation(project(":pipeline-service-test-utils:pipeline-test-platform"))
    testImplementation("io.projectreactor:reactor-test")
    testAnnotationProcessor(mn.micronaut.inject.java)
    // Testcontainers dependencies
    testImplementation("org.testcontainers:junit-jupiter:1.21.0")
    testImplementation("org.testcontainers:consul:1.21.0")
    testImplementation("org.awaitility:awaitility")
    testImplementation(mn.testcontainers.consul)
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
