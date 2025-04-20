// bom/build.gradle.kts
plugins {
    `java-platform` // Core plugin for creating the BOM [21, 22]
    `maven-publish` // Needed to publish the BOM
    // alias(libs.plugins.release) // Optional: If versioning/releasing the BOM itself [24]
}

// Inherit group and version from root project (or manage independently)
group = rootProject.group
version = rootProject.version

javaPlatform {
    // allowDependencies() // Enable if direct dependencies needed (rare for BOM) [21, 23]
}

dependencies {
    constraints {
        // --- Micronaut Dependencies ---
        // Import the Micronaut Platform BOM using the 'libs' accessor here,
        // as 'mn' might not be reliably available in this specific subproject context.
        api(platform(libs.micronaut.platform)) // Use libs accessor [Updated]

        // --- Non-Micronaut Project Dependencies (using aliases from libs.versions.toml) ---
        api(libs.protobuf.java)
        // api(libs.protobuf.kotlin) // If used
        api(libs.grpc.stub)       // If used
        api(libs.grpc.protobuf)   // If used
        api(libs.guava)
        api(libs.jackson.databind) // Non-Micronaut Jackson
        api(libs.commons.lang3)

        // --- Testing Dependencies ---
        api(libs.bundles.testing.jvm) // Constrain JUnit versions via bundle

        // --- Other Third-Party Dependencies ---
        api(libs.slf4j.api)

        // --- Constraints for your own modules (if published separately) ---
        // Example for unified versioning (constraining to the BOM's version):
        api("${rootProject.group}:protobuf-models:${rootProject.version}")
        api("${rootProject.group}:util:${rootProject.version}")
        // Add constraints for other internal libraries like core, test-utils if they are published
    }
}

// Configure Publishing
publishing {
    publications {
        create<MavenPublication>("mavenJavaPlatform") {
            from(components["javaPlatform"]) // Publish the platform component [23]
            // Use group and version from the project
            groupId = project.group.toString()
            artifactId = project.name // Artifact ID will be 'bom'
            version = project.version.toString()

            pom {
                name.set("My Pipeline System BOM")
                description.set("Bill of Materials for My Pipeline System components")
                // Add license, developer info, etc.
            }
        }
    }
    repositories {
        mavenLocal() // Publish to local Maven repo (~/.m2/repository)
        // maven { url = uri("...") credentials { ... } } // Remote repo config
    }
}