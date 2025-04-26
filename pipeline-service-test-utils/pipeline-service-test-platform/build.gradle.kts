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

    api(project(":pipeline-service-test-utils:micronaut-test-consul-container"))
    api(project(":pipeline-service-test-utils:micronaut-kafka-registry"))
    testImplementation(project(":pipeline-service-test-utils:micronaut-kafka-registry"))
    // Pipeline service core
    api(project(":pipeline-service-core"))
    // Test containers

    // Micronaut
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.discovery.client)
    implementation(mn.micronaut.test.core)
    implementation(mn.micronaut.test.junit5)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.kafka)
    implementation(mn.micronaut.test.resources.kafka)

    // Testcontainers
    implementation("org.testcontainers:junit-jupiter")
    implementation("org.testcontainers:testcontainers")
    implementation("org.testcontainers:kafka")

    // Protobuf models from central project
    implementation(project(":protobuf-models"))

    // Testing
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation(mn.micronaut.http.server.netty)

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
    version("4.8.2")
    processing {
        incremental(true)
        annotations("com.krickert.search.test.platform.*", "com.krickert.search.test.apicurio.*")
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
                name.set("Pipeline Service Test Platform")
                description.set("Test platform for pipeline services using real Consul, Kafka, and Apicurio")

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
            }
        }
    }
}
