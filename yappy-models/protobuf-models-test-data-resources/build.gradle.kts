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

    // Depend on the protobuf-models project
    implementation(project(":yappy-models:protobuf-models"))

    // Google Protobuf dependencies
    implementation(mn.protobuf.java)
    implementation(mn.protobuf.java.util)

    // Jackson dependencies
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jdk8:2.19.0")
    implementation("com.fasterxml.jackson.module:jackson-module-parameter-names:2.19.0")
    implementation(mn.jackson.databind)

    // Guava for collections
    implementation("com.google.guava:guava:33.0.0-jre")

    // Annotation processors
    annotationProcessor(libs.lombok)
    compileOnly(libs.lombok)

    // Test dependencies
    testImplementation(libs.bundles.testing.jvm)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")

    // Jimfs for in-memory file system testing
    testImplementation("com.google.jimfs:jimfs:1.3.0")
}

// Create source directories
sourceSets {
    main {
        java {
            srcDirs("src/main/java")
        }
        resources {
            srcDirs("src/main/resources")
        }
    }
    test {
        java {
            srcDirs("src/test/java")
        }
        resources {
            srcDirs("src/test/resources")
        }
    }
}

// Set duplicates strategy for resources
tasks.named<ProcessResources>("processResources") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Set duplicates strategy for sourcesJar
tasks.named<Jar>("sourcesJar") {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Protobuf Models Test Data Resources")
                description.set("Test data resources for protobuf model classes")

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

