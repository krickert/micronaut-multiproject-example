import com.google.protobuf.gradle.id

var grpcVersion = libs.versions.grpc.get()
var protobufVersion = libs.versions.protobuf.get()

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
    testImplementation(mn.mockito.core)

    implementation(mn.protobuf.java)
    // Use the alias from libs.versions.toml
    implementation(libs.protobufcommon)
    implementation(libs.protobuf.util)
    api(mn.grpc.protobuf)
    api(mn.grpc.stub)
    api(mn.grpc.services)
    implementation(mn.slf4j.api)
    implementation(mn.logback.classic)
    implementation(mn.javax.annotation.api)
    implementation(mn.guava)
    implementation(libs.commons.lang3)

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testImplementation("com.google.jimfs:jimfs:1.3.0")
}


// In build.gradle.kts
// In build.gradle.kts

protobuf {
    protoc {
        // This sets the protoc compiler artifact
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }

    plugins {
        // Define the gRPC plugin; Micronaut doesn't add this automatically
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }

    generateProtoTasks {
        all().forEach { task ->
            // The Micronaut plugin automatically adds the java builtin.
            // We only need to add the grpc plugin.
            task.plugins {
                id("grpc")
            }
        }
    }
}