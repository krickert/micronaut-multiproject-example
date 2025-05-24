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
    implementation(mn.grpc.protobuf)
    implementation(mn.grpc.stub)
    implementation(mn.slf4j.api)
    implementation(mn.logback.classic)
    implementation(mn.javax.annotation.api)
    implementation(mn.guava)
    implementation(libs.commons.lang3)

    testImplementation(mn.junit.jupiter.api)
    testRuntimeOnly(mn.junit.jupiter.engine)
    testImplementation("com.google.jimfs:jimfs:1.3.0")
}

// Simplified protobuf configuration
// Inform IDEs like IntelliJ IDEA, Eclipse or NetBeans about the generated code.
sourceSets {
    main {
        java {
            srcDirs("build/generated/sources/proto/main/grpc")
            srcDirs("build/generated/sources/proto/main/java")
            srcDirs("build/generated/sources/proto/main/python")
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
                create("python")
            }
        }
    }
}
