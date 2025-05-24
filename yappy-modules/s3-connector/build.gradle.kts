import io.micronaut.testresources.buildtools.KnownModules

plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.s3connector"

repositories {
    mavenCentral()
}

dependencies {
    testAnnotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.javax.annotation.api)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")

    implementation(project(":yappy-models:protobuf-models"))
    implementation(mn.grpc.services)
    implementation(mn.grpc.stub)
    implementation(mn.micronaut.http.client.core)
    implementation("io.micronaut.grpc:micronaut-protobuff-support")

    // AWS SDK for S3
    implementation("io.micronaut.aws:micronaut-aws-sdk-v2")
    implementation("software.amazon.awssdk:s3:2.25.11")

    // Kafka dependencies
    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")
    testImplementation(mn.assertj.core)

    // Lombok for builder pattern
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    // Testing dependencies
    testImplementation(mn.junit.jupiter.params)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")

    // Test resources
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
}

application {
    mainClass = "com.krickert.yappy.modules.s3connector.Application"
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.modules.s3connector.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
    }
}
