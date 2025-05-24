plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.library") version "4.5.3" // Match the version used in apicurio-test-resource
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withJavadocJar()
    withSourcesJar()
}

micronaut {
    version("4.8.2") // Match the version used in apicurio-test-resource
    // No testResources block needed here as this IS a test resource provider
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))

    // Micronaut test resources
    api(mn.micronaut.test.resources.core)
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")

    // TestContainers core
    api("org.testcontainers:testcontainers")

    // Logging
    api(mn.logback.classic)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resources - OpenSearch 3.0")
                description.set("Test resource providers for Yappy platform")
            }
        }
    }
}
