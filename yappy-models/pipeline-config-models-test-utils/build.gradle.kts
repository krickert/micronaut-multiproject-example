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

    // Depend on the pipeline-config-models project
    implementation(project(":yappy-models:pipeline-config-models"))

    // Jackson dependencies
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.19.0")
    implementation(mn.jackson.databind)

    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.5.6")

    // Annotation processors
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)

    // Test dependencies
    testImplementation(libs.bundles.testing.jvm)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
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
                name.set("Pipeline Config Models Test Utils")
                description.set("Test utilities for pipeline configuration model classes")

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
