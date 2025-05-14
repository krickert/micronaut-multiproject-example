plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.chunker"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.protobuff.support)
    implementation(mn.protobuf.java.util)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.jackson.databind)
    implementation(mn.javax.annotation.api)
    // Added for metadata extraction
    implementation("org.apache.opennlp:opennlp-tools:2.4.0") // Or 2.3.3 if any issues, but 2.4.0 should be fine. Let's use 2.4.0 for now.
    implementation("org.apache.commons:commons-lang3:3.14.0") // Sticking to a slightly older, very stable version. 3.17.0 is newest.
    implementation("org.apache.commons:commons-text:1.12.0")   // Sticking to a slightly older, very stable version. 1.13.1 is newest.

    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation(project(":yappy-models:protobuf-models"))
}


application {
    mainClass = "com.krickert.yappy.modules.chunker.ChunkerApplication"
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
        annotations("com.krickert.yappy.modules.chunker.*")
    }
}



