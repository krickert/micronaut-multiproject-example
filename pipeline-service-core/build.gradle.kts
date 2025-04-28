// File: pipeline-service-core/build.gradle.kts
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
        annotations("com.krickert.search.pipeline.*")
    }
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Micronaut dependencies using mn catalog
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.validation)

    // API dependencies - these are exposed to consumers of the library
    api(mn.micronaut.inject)
    api(mn.micronaut.serde.api)
    api(mn.micronaut.serde.jackson)
    api(mn.micronaut.runtime)
    api(mn.micronaut.validation)
    api(mn.micronaut.grpc.server.runtime)
    api(mn.micronaut.grpc.annotation)
    api(mn.micronaut.kafka)
    api(mn.rxjava3)


    // Project dependencies
    api(project(":protobuf-models"))
    api(project(":util"))

    // Implementation dependencies - these are not exposed to consumers
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation("io.micronaut.discovery:micronaut-discovery-client")
    compileOnly(mn.lombok)

    // AWS Glue Schema Registry dependencies
    implementation("software.amazon.glue:schema-registry-serde:1.1.23") {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }
    implementation("software.amazon.msk:aws-msk-iam-auth:2.2.0")
    implementation("software.amazon.awssdk:url-connection-client:2.30.31")

    // Apicurio Registry dependencies
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6") {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }

    // Explicitly include Wire library to ensure consistent version
    implementation("com.squareup.wire:wire-schema")
    implementation("com.squareup.wire:wire-runtime")

    // Testing dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(project(":pipeline-service-test-utils:micronaut-test-consul-container"))
    testImplementation(project(":pipeline-service-test-utils:pipeline-test-platform"))
    testAnnotationProcessor(mn.micronaut.inject.java)
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Pipeline Service Core")
                description.set("Core library for pipeline service implementation")

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
