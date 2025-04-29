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
    annotationProcessor(libs.bundles.micronaut.annotation.processors)

    // API dependencies - these are exposed to consumers of the library
    api(mn.micronaut.inject)
    api(mn.micronaut.serde.api)
    api(mn.micronaut.serde.jackson)
    api(mn.micronaut.runtime)
    api(mn.micronaut.validation)
    api(mn.micronaut.grpc.server.runtime)
    api(mn.micronaut.grpc.health)
    api(mn.micronaut.grpc.runtime)
    api(mn.micronaut.grpc.annotation)
    api(mn.grpc.services)
    api(mn.grpc.protobuf)

    api(mn.micronaut.kafka)
    api(mn.rxjava3)
    api(mn.micronaut.reactor)
    api(mn.micronaut.protobuff.support)
    api(mn.micronaut.management)
    api(mn.micronaut.http.client)
    api(mn.micronaut.http.server.netty)
    api(mn.micronaut.discovery.client)
    api(mn.micronaut.discovery.core)
    api(mn.micronaut.aws.sdk.v2)
    // AWS Glue Schema Registry dependencies
    api(libs.amazon.glue) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }
    api(libs.amazon.msk.iam)
    api(libs.amazon.connection.client)

    // Implementation dependencies - these are not exposed to consumers
    api(libs.slf4j.api)
    api(libs.logback.classic)
    compileOnly(mn.lombok)

    // Apicurio Registry dependencies
    api(libs.apicurio.serde) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }

    // Explicitly include Wire library to ensure consistent version
    api("com.squareup.wire:wire-schema")
    api("com.squareup.wire:wire-runtime")

    // Project dependencies
    api(project(":protobuf-models"))
    api(project(":util"))

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
