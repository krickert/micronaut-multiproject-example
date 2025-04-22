// File: protobuf-models/build.gradle.kts
plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform(project(":bom")))
    testImplementation(platform(project(":bom")))

    implementation(libs.protobuf.java)
    implementation(libs.slf4j.api)
    implementation(libs.guava)
    implementation(libs.commons.lang3)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// Simplified protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}