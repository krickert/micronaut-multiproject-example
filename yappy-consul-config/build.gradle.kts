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
        annotations("com.krickert.search.config.consul.**", "com.krickert.testcontainers.consul.*")
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
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)
    testResourcesImplementation(project(":yappy-test-resources"))

    testImplementation(project(":yappy-test-resources"))

    api(mn.micronaut.serde.api)
    api(mn.micronaut.serde.jackson)
    api(mn.micronaut.jackson.databind)
    // https://mvnrepository.com/artifact/org.kiwiproject/consul-client
    implementation("org.kiwiproject:consul-client:1.5.1")
    api(project(":yappy-models:schema-registry-models"))
    api(project(":yappy-models:pipeline-config-models"))
    // https://mvnrepository.com/artifact/com.networknt/json-schema-validator
    api("com.networknt:json-schema-validator:1.5.6")
    // https://mvnrepository.com/artifact/org.jgrapht/jgrapht-core
    implementation("org.jgrapht:jgrapht-core:1.5.2")
    testImplementation(mn.mockito.junit.jupiter)
    runtimeOnly(mn.logback.classic) // This line was missing from your provided snippet, re-add if it was there
    implementation(mn.micronaut.reactor.http.client)
    implementation(mn.javax.annotation.api)
    implementation(mn.micronaut.context)
    testResourcesImplementation("org.testcontainers:consul:1.21.0")
}

// Add this block to explicitly configure the Mockito agent
tasks.withType<Test>().configureEach {
    val mockitoCoreJar = configurations.testRuntimeClasspath.get()
        .files.find { it.name.startsWith("mockito-core") }
    if (mockitoCoreJar != null) {
        jvmArgs("-javaagent:${mockitoCoreJar.absolutePath}")
        logger.lifecycle("Configured Mockito agent: ${mockitoCoreJar.absolutePath}")
    } else {
        logger.warn("WARNING: mockito-core.jar not found in testRuntimeClasspath for agent configuration. Mockito inline mocking may not work as expected or show warnings.")
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
