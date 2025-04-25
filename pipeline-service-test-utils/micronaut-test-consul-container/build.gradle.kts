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
    implementation(mn.micronaut.discovery.client)
    implementation(mn.micronaut.test.core)
    implementation(mn.micronaut.test.junit5)
    implementation(mn.micronaut.serde.jackson)

    // Testcontainers
    implementation("org.testcontainers:junit-jupiter")
    implementation("org.testcontainers:testcontainers")

    // Testing
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("io.projectreactor:reactor-core")
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
                name.set("Micronaut Test Consul Container")
                description.set("Consul container for Micronaut tests")
                url.set("https://github.com/krickert/micronaut-test-consul-container")

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
                    connection.set("scm:git:git://github.com/krickert/micronaut-test-consul-container.git")
                    developerConnection.set("scm:git:ssh://github.com/krickert/micronaut-test-consul-container.git")
                    url.set("https://github.com/krickert/micronaut-test-consul-container")
                }
            }
        }
    }
}
