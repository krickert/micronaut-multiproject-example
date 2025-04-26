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

    // Micronaut
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.test.core)
    implementation(mn.micronaut.test.junit5)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.kafka)
    implementation(mn.micronaut.test.resources.kafka)
    implementation(mn.micronaut.aws.sdk.v2)

    // AWS for Moto
    implementation("software.amazon.glue:schema-registry-serde:1.1.23")
    implementation("software.amazon.msk:aws-msk-iam-auth:2.2.0")
    implementation("software.amazon.awssdk:url-connection-client:2.30.31")

    // Apicurio Registry
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")

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