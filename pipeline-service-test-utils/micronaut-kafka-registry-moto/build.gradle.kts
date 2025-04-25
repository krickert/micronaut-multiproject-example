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
    annotationProcessor("org.projectlombok:lombok")

    // Schema Registry Interface
    api(project(":pipeline-service-test-utils:micronaut-kafka-registry-core"))

    // Micronaut
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.test.core)
    implementation(mn.micronaut.test.junit5)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.aws.sdk.v2)
    implementation(mn.micronaut.kafka)
    implementation(mn.micronaut.test.resources.kafka)

    // AWS
    implementation("software.amazon.glue:schema-registry-serde:1.1.23")
    implementation("software.amazon.msk:aws-msk-iam-auth:2.2.0")
    implementation("software.amazon.awssdk:url-connection-client:2.30.31")

    // Protobuf models from central project
    testImplementation(project(":protobuf-models"))

    // Testing
    implementation("org.testcontainers:junit-jupiter")
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:kafka")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")

    // Other
    implementation("javax.annotation:javax.annotation-api")
    implementation("jakarta.annotation:jakarta.annotation-api")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly(mn.logback.classic)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("kafka.registry.moto.*")
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
                name.set("Micronaut Kafka Registry Moto")
                description.set("Moto implementation for Micronaut Kafka Registry")
                url.set("https://github.com/yourusername/micronaut-kafka-registry")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("krickert")
                        name.set("Kevin Rickert")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/yourusername/micronaut-kafka-registry.git")
                    developerConnection.set("scm:git:ssh://github.com/yourusername/micronaut-kafka-registry.git")
                    url.set("https://github.com/yourusername/micronaut-kafka-registry")
                }
            }
        }
    }
}
