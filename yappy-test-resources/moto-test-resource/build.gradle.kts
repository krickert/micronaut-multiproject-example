plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.library") version "4.5.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withJavadocJar()
    withSourcesJar()
}

micronaut {
    version("4.8.2")
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))

    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")
    // No specific Testcontainers module for Moto, as it uses GenericContainer

    api("ch.qos.logback:logback-classic:1.5.6")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Moto Test Resource Provider")
                description.set("Test resource provider for Moto Server")
                // ... licenses ...
            }
        }
    }
}