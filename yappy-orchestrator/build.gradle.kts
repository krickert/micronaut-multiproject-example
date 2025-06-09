plugins {
    alias(libs.plugins.micronaut.application)
    id("io.micronaut.crac") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.bmuschko.docker-remote-api") version "9.4.0"
    // Disabling AOT plugin to fix build issues
    // id("io.micronaut.aot") version "4.5.3"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.search.pipeline"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Annotation processors
    annotationProcessor(libs.lombok)
    annotationProcessor(mn.micronaut.http.validation)
    annotationProcessor("io.micronaut.langchain4j:micronaut-langchain4j-processor")
    annotationProcessor(mn.micronaut.openapi)

    // Sub-module dependencies
    implementation(project(":yappy-orchestrator:engine-core"))
    implementation(project(":yappy-orchestrator:engine-bootstrap"))
    implementation(project(":yappy-orchestrator:engine-registration"))
    implementation(project(":yappy-orchestrator:engine-health"))
    implementation(project(":yappy-orchestrator:engine-kafka"))
    implementation(project(":yappy-orchestrator:engine-pipeline"))
    implementation(project(":yappy-orchestrator:engine-grpc"))
    implementation(project(":yappy-orchestrator:engine-config"))

    // Implementation dependencies
    implementation(mn.micrometer.context.propagation)
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.jackson.databind)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.aws.sdk.v2)
    implementation(mn.micronaut.crac)
    implementation(mn.micronaut.discovery.client)
    implementation(mn.micronaut.kafka)
    implementation("io.micronaut.langchain4j:micronaut-langchain4j-openai")
    implementation(mn.micronaut.micrometer.core)
    implementation(mn.micronaut.micrometer.observation)
    implementation(mn.micronaut.micrometer.registry.jmx)
    implementation(mn.micronaut.micrometer.registry.statsd)
    implementation(mn.micronaut.reactor)
    implementation(mn.micronaut.reactor.http.client)

    // Project dependencies - handle both root project and standalone builds
    if (rootProject.name == "yappy-platform-build") {
        // Building from root project
        implementation(project(":yappy-test-resources"))
        implementation(project(":yappy-models:protobuf-models"))
        implementation(project(":yappy-models:pipeline-config-models"))
        implementation(project(":yappy-consul-config"))
        implementation(project(":yappy-kafka-slot-manager"))

        // Test resources
        testImplementation(project(":yappy-test-resources:consul-test-resource"))

        // Test dependencies
        testImplementation(project(":yappy-models:pipeline-config-models-test-utils"))
        testImplementation(project(":yappy-models:protobuf-models-test-data-resources"))
    } else {
        // Building from yappy-orchestrator directory
        implementation("com.krickert.search:yappy-test-resources")
        implementation("com.krickert.search:protobuf-models")
        implementation("com.krickert.search:pipeline-config-models")
        implementation("com.krickert.search:yappy-consul-config")
        implementation("com.krickert.search:yappy-kafka-slot-manager")

        // Test resources
        testImplementation("com.krickert.search:consul-test-resource")

        // Test dependencies
        testImplementation("com.krickert.search:pipeline-config-models-test-utils")
        testImplementation("com.krickert.search:protobuf-models-test-data-resources")
    }

    // Compile-only dependencies
    compileOnly(mn.micronaut.openapi.annotations)
    compileOnly(libs.lombok)

    // Runtime dependencies
    runtimeOnly(libs.logback.classic)
    runtimeOnly(mn.snakeyaml)

    // Test dependencies
    testImplementation(mn.assertj.core)
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation(mn.junit.jupiter.params)
    testImplementation("org.junit.platform:junit-platform-suite-engine")
    testImplementation(mn.mockito.core)
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:testcontainers")

    // Development-only dependencies
    developmentOnly(mn.micronaut.control.panel.management)
    developmentOnly(mn.micronaut.control.panel.ui)
}


application {
    mainClass = "com.krickert.search.pipeline.Application"
}
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}


graalvmNative.toolchainDetection = false

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.pipeline.*")
    }
    testResources {
        sharedServer = true
    }
    // AOT configuration disabled to fix build issues

}

// Enable tests
tasks.withType<Test> {
    useJUnitPlatform()
}

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
    
    // Ensure module containers are built first
    dependsOn(
        ":yappy-modules:chunker:dockerBuild",
        ":yappy-modules:tika-parser:dockerBuild"
    )
}
