plugins {
    `java-library`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // Apply BOM/platform dependencies
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Only Jackson and Lombok dependencies
    implementation(libs.jackson.databind)
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3") // For Java 8 date/time support

    // Annotation processors
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)

    // Test dependencies
    testImplementation(libs.bundles.testing.jvm)
}

// Create test source directories
sourceSets {
    test {
        java {
            srcDirs("src/test/java")
        }
        resources {
            srcDirs("src/test/resources")
        }
    }
}

// Set duplicates strategy for test resources
tasks.named<ProcessResources>("processTestResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Pipeline Config Models")
                description.set("Model classes for pipeline configuration")

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
