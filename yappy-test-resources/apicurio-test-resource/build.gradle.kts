plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.library") version "4.5.3" // Or your current Micronaut plugin version
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withJavadocJar()
    withSourcesJar()
}

micronaut {
    version("4.8.2") // Ensure this aligns with your Micronaut version
    // No testResources block needed here as this IS a test resource provider
}

dependencies {
    // Apply BOM/platform dependencies
    implementation(platform(project(":bom"))) // Assumes BOM is a sibling subproject
    annotationProcessor(platform(project(":bom")))
    // testImplementation(platform(project(":bom"))) // If you add tests for the provider
    // testAnnotationProcessor(platform(project(":bom"))) // If you add tests for the provider

    // Micronaut test resources
    api(mn.micronaut.test.resources.core)
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")

    // TestContainers core
    api("org.testcontainers:testcontainers")

    // Apicurio Registry SerDe (as it's used by the provider for config keys)
    api(libs.apicurio.serde) {
        // Exclude transitive Wire dependencies if still needed
        exclude(group = "com.squareup.wire")
    }
    // Logging
    api(mn.logback.classic) // Or mn.logback.classic if preferred
}

publishing { // Optional: if you publish this module independently
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            // Add POM details (name, description, licenses)
            pom {
                name.set("Apicurio Test Resource Provider")
                description.set("Test resource provider for Apicurio Registry")
                // ... licenses ...
            }
        }
    }
}