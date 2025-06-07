plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.embedder"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.protobuff.support)
    implementation(mn.protobuf.java.util)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.jackson.databind)
    implementation(mn.javax.annotation.api)

    // https://mvnrepository.com/artifact/ai.djl.huggingface/tokenizers
    implementation("ai.djl.huggingface:tokenizers:0.33.0")
    implementation("ai.djl.pytorch:pytorch-model-zoo:0.33.0")
    implementation("ai.djl.pytorch:pytorch-jni:2.5.1-0.33.0")
    
    // Add GPU support for x86_64 architecture
    if (System.getProperty("os.arch") == "amd64") {
        implementation("ai.djl.pytorch:pytorch-native-cu124:2.5.1")
    }
    
    // Apache Commons dependencies
    implementation("org.apache.commons:commons-lang3:3.14.0")
    implementation("org.apache.commons:commons-text:1.12.0")

    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation(project(":yappy-models:protobuf-models"))

    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.javax.annotation.api)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")

    implementation(mn.grpc.services)
    implementation(mn.grpc.stub)
    implementation(mn.micronaut.http.client.core)
    implementation("io.micronaut.grpc:micronaut-protobuff-support")
    testImplementation(mn.mockito.core)
}

application {
    mainClass = "com.krickert.yappy.modules.embedder.EmbedderApplication"
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
        annotations("com.krickert.yappy.modules.embedder.*")
    }
}