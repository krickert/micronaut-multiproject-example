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
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.testresourcesetup.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        // Add all test resource modules
        additionalModules.add("kafka")
        additionalModules.add("opensearch")
        clientTimeout.set(120) // Increase timeout to ensure all containers have time to start
        sharedServer.set(true) // Set shared server to true to share containers with other projects
    }
}

dependencies {
    // Apply BOM/platform dependencies
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Annotation processors
    annotationProcessor(libs.bundles.micronaut.annotation.processors)
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)

    // JSON processing
    implementation(mn.micronaut.serde.api)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.jackson.databind)

    // Test resources dependencies
    testImplementation(project(":yappy-test-resources"))
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testImplementation(project(":yappy-test-resources:moto-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:opensearch3-test-resource"))

    // Test dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(mn.assertj.core)
    testImplementation(mn.logback.classic)

    // AWS SDK for Moto tests
    testImplementation("software.amazon.awssdk:glue:2.20.56")

    // Kafka client for Kafka tests
    testImplementation("org.apache.kafka:kafka-clients:3.4.0")
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resource Setup")
                description.set("Test resource setup for Yappy platform")
            }
        }
    }
}
