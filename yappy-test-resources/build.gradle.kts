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
    testRuntime("junit5")
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

    // Micronaut test resources
    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")

    // TestContainers dependencies
    api("org.testcontainers:testcontainers")
    api("org.testcontainers:junit-jupiter")
    api("org.testcontainers:consul:1.21.0")
    api("org.testcontainers:kafka:1.21.0")

    // Apicurio Registry dependencies
    api(libs.apicurio.serde) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }

    // Logging
    api(libs.slf4j.api)
    api(libs.logback.classic)
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resources")
                description.set("Test resource providers for Yappy platform")

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
