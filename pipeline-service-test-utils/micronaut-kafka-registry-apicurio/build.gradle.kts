plugins {
    alias(libs.plugins.micronaut.library)
    id("io.micronaut.test-resources") version libs.versions.micronautPlugins.get()
    alias(libs.plugins.protobuf)
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
    implementation(mn.micronaut.kafka)
    implementation(mn.micronaut.test.resources.kafka)

    // Apicurio Registry
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")

    // Protobuf and gRPC
    implementation(libs.protobuf.java)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)

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
        annotations("kafka.registry.apicurio.*")
    }
    testResources {
        sharedServer = true
    }
}

sourceSets {
    test {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    plugins {
        create("grpc") {
            artifact = libs.grpc.protocGen.get().toString()
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Micronaut Kafka Registry Apicurio")
                description.set("Apicurio implementation for Micronaut Kafka Registry")
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
