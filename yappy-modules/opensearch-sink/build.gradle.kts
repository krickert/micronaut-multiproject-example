import io.micronaut.testresources.buildtools.KnownModules

plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.opensearchsink"

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

    // OpenSearch dependencies
    implementation("io.micronaut.opensearch:micronaut-opensearch-httpclient5")

    // Protobuf JSON conversion
    implementation("com.google.protobuf:protobuf-java-util:3.25.3")

    // Kafka dependencies
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.7")
    testImplementation(mn.assertj.core)
    // https://mvnrepository.com/artifact/org.opensearch.client/opensearch-rest-high-level-client
    implementation("org.opensearch.client:opensearch-rest-high-level-client:3.0.0")
    // https://mvnrepository.com/artifact/org.opensearch.client/opensearch-rest-client
    implementation("org.opensearch.client:opensearch-rest-client:3.0.0")
    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.0.86")

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
    testImplementation(project(":yappy-test-resources:opensearch3-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:opensearch3-test-resource"))
    implementation("org.opensearch:protobufs:0.3.0")
    // https://mvnrepository.com/artifact/org.fusesource.jansi/jansi
    implementation("org.fusesource.jansi:jansi:2.4.2")
    // OpenSearch TestContainer
    //testImplementation("org.opensearch:opensearch-testcontainers:3.0.0")
}

application {
    mainClass = "com.krickert.yappy.modules.opensearchsink.Application"
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
        annotations("com.krickert.yappy.modules.opensearchsink.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
        debugServer.set(true)

    }
}