// protobuf-models/build.gradle.kts
import com.google.protobuf.gradle.*

plugins {
    `java-library` // Using Java for helper code
    alias(libs.plugins.protobuf) // Apply protobuf plugin
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
    implementation(platform(project(":bom"))) // Import custom BOM
    testImplementation(platform(project(":bom")))

    implementation(libs.protobuf.java) // Protobuf runtime
    // implementation(libs.grpc.stub) // If using gRPC
    implementation(libs.guava) // Example utility
    implementation(libs.commons.lang3)
    testImplementation(libs.bundles.testing.jvm)
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            // Java generation is usually enabled by default for Java projects.
            // This block is only needed if passing specific options to the java generator
            // task.builtins { create("java") { option("lite") } }

            // Configure gRPC Java generation if needed
            // task.plugins { create("grpc") { artifact = libs.grpc.protocGen.get().toString() } }

            // Configure Kotlin generation if needed
            // task.builtins { create("kotlin") {} }

            task.outputs.upToDateWhen { false } // Ensure regeneration on proto changes
        }
    }
}

sourceSets.main {
    java { srcDirs(layout.buildDirectory.dir("generated/source/proto/main/java")) }
    // Add grpc dir if needed: srcDirs(layout.buildDirectory.dir("generated/source/proto/main/grpc"))
}

// Publishing configuration (if this library is published)
// publishing { ... }