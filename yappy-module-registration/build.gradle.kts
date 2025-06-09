plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.registration"

repositories {
    mavenCentral()
}

dependencies {
    // Micronaut core
    implementation("io.micronaut:micronaut-runtime")
    implementation("io.micronaut:micronaut-inject")
    implementation("io.micronaut.picocli:micronaut-picocli")
    implementation("info.picocli:picocli")

    // gRPC dependencies
    implementation("io.micronaut.grpc:micronaut-grpc-runtime")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-protobuf")
    // https://mvnrepository.com/artifact/io.grpc/grpc-api
    implementation("io.grpc:grpc-api")
    implementation("io.grpc:grpc-netty-shaded")

    // Protobuf models
    implementation(project(":yappy-models:protobuf-models"))

    // yappy-consul-config for validation and config management
    implementation(project(":yappy-consul-config"))
    implementation(project(":yappy-models:pipeline-config-models"))

    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.5.6")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    
    // Reactive support for using SchemaValidationService
    implementation("io.projectreactor:reactor-core")

    // Logging
    runtimeOnly("ch.qos.logback:logback-classic")

    // Annotation processing
    annotationProcessor("io.micronaut:micronaut-inject-java")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    annotationProcessor("info.picocli:picocli-codegen")
    annotationProcessor("org.projectlombok:lombok")
    compileOnly("org.projectlombok:lombok")

    // Testing
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core")
    
    // Test Resources support
    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    
    // Consul client for verification in tests
    testImplementation("com.ecwid.consul:consul-api:1.4.5")
}

application {
    mainClass = "com.krickert.yappy.registration.YappyRegistrationCli"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

sourceSets {
    main {
        java {
            // Add the directories where protoc generates the .java files
            srcDir(layout.buildDirectory.dir("generated/source/proto/main/java"))
            srcDir(layout.buildDirectory.dir("generated/source/proto/main/grpc"))
        }
    }
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.registration.*")
    }
    testResources {
        sharedServer = true
    }
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    isZip64 = true
    archiveBaseName.set("yappy-registration-cli")
    archiveClassifier.set("")
}