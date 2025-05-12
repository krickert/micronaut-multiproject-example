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

    api(mn.micronaut.test.resources.core)
    api(mn.micronaut.test.resources.testcontainers)
    api(mn.testcontainers.core)
    api(mn.testcontainers.consul)
    api(mn.logback.classic)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("Consul Test Resource Provider")
                description.set("Test resource provider for HashiCorp Consul")
                // ... licenses ...
            }
        }
    }
}