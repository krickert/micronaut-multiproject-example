plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.chunker"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Apply BOM/platform dependencies
    // When building from the root project
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))


    annotationProcessor(mn.micronaut.serde.processor)
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

    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")

    implementation(mn.grpc.services)
    implementation(mn.grpc.stub)
    implementation(mn.micronaut.http.client.core)

    // Test dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core")
    testImplementation(project(":yappy-models:protobuf-models-test-data-resources"))
}


application {
    mainClass = "com.krickert.yappy.modules.chunker.ChunkerApplication"
}
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}


// graalvmNative.toolchainDetection = false


micronaut {
    runtime("netty")

    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.modules.chunker.*")
    }
    testResources {
        sharedServer = true
    }
}

// Docker configuration for native image
tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}

// Configure Docker build to handle larger contexts
docker {
    // Use environment variable or default socket
    url = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"

    // API version compatibility
    apiVersion = "1.41"
}

// Configure the dockerBuild task
tasks.named<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuild") {
    val imageName = project.name.lowercase()
    images.set(listOf(
        "${imageName}:${project.version}",
        "${imageName}:latest"
    ))
}
