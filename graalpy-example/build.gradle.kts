import com.google.protobuf.gradle.*

plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.google.protobuf") version "0.9.2"
    id("org.graalvm.python") version "24.2.0"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "0.1"
group = "com.krickert.search.python"

repositories {
    mavenCentral()
}
// Install required Python packages for the gRPC server
graalPy {
    packages.add("termcolor==2.2")
    packages.add("grpcio==1.59.0")
    packages.add("protobuf==4.24.4")
}
dependencies {
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micronaut:micronaut-discovery-core")
    implementation("io.micronaut.graal-languages:micronaut-graalpy")
    implementation("io.micronaut.grpc:micronaut-grpc-runtime")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("javax.annotation:javax.annotation-api")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")


    // Add test dependencies for gRPC testing
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testImplementation("io.grpc:grpc-testing:1.69.1")
    testImplementation("io.grpc:grpc-inprocess:1.69.1")
    testImplementation("org.junit.jupiter:junit-jupiter-api")
}


application {
    mainClass = "com.krickert.search.python.Application"
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
        resources {
            // Include locally generated Python stubs
            srcDirs("build/generated/source/proto/main/python")
        }
    }
}



protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.6"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.69.1"
        }
        // Add Python plugin
        id("python")
    }
    generateProtoTasks {
        ofSourceSet("main").forEach {
            it.plugins {
                // Apply the "grpc" plugin whose spec is defined above, without options.
                id("grpc")
                // Generate Python stubs
                id("python")
            }
        }
    }
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.python.*")
    }
}

// Add dependency between tasks and generateProto
tasks.named("processResources") {
    dependsOn("generateProto")
}

tasks.named("inspectRuntimeClasspath") {
    dependsOn("generateProto")
}


tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}

//tag::graalpy-gradle-plugin[]
// Configure GraalPy to use the community edition
tasks.withType<JavaExec> {
    systemProperty("org.graalvm.python.distribution", "community")
}
//end::graalpy-gradle-plugin[]
