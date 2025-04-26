plugins {
    alias(libs.plugins.micronaut.library)
    id("io.micronaut.test-resources") version libs.versions.micronautPlugins.get()
    `maven-publish`
}

dependencies {
    // Use the root BOM
    implementation(platform(project(":bom")))

    // Annotation processors
    annotationProcessor(mn.micronaut.serde.processor)
    annotationProcessor(mn.lombok)

    // Micronaut
    api(mn.micronaut.discovery.core)
    api(mn.micronaut.test.core)
    api(mn.micronaut.test.junit5)
    api(mn.micronaut.grpc.runtime)
    api(mn.micronaut.serde.jackson)
    api(mn.micronaut.kafka)
    api(mn.micronaut.test.resources.kafka)
    api(mn.micronaut.aws.sdk.v2)

    // AWS for Moto
    api("software.amazon.glue:schema-registry-serde:1.1.23")
    api("software.amazon.msk:aws-msk-iam-auth:2.2.0")
    api("software.amazon.awssdk:url-connection-client:2.30.31")

    // Apicurio Registry
    api("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")

    // Protobuf models from central project
    api(project(":protobuf-models"))

    // Testing
    api("org.testcontainers:junit-jupiter")
    api(mn.testcontainers.core)
    api(mn.testcontainers.kafka)
    api(mn.assertj.core)
    testImplementation("org.awaitility:awaitility")

    // Other
    api(mn.javax.annotation.api)
    api(mn.jakarta.annotation.api)
    compileOnly(mn.lombok)
    implementation(libs.logback.classic)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.test.*")
    }
    testResources {
        sharedServer = true
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Micronaut Kafka Registry")
                description.set("Combined library for Micronaut Kafka Registry with Moto and Apicurio support")
                url.set("https://github.com/krickert/micronaut-kafka-registry")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("krickert")
                        name.set("Kristian Rickert")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/krickert/micronaut-kafka-registry.git")
                    developerConnection.set("scm:git:ssh://github.com/krickert/micronaut-kafka-registry.git")
                    url.set("https://github.com/krickert/micronaut-kafka-registry")
                }
            }
        }
    }
}
