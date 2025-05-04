
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

    implementation(libs.protobuf.java)
    // Use the alias from libs.versions.toml
    implementation(libs.protobufcommon)
    implementation(libs.protobuf.util)
    implementation(libs.grpc.protobuf)
    implementation(libs.grpc.stub)
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)
    implementation(mn.javax.annotation.api)
    implementation(libs.guava)
    implementation(libs.commons.lang3)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

// Simplified protobuf configuration
// Inform IDEs like IntelliJ IDEA, Eclipse or NetBeans about the generated code.
sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}
