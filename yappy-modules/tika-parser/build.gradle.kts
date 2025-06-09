plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.tikaparser"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    // Apply BOM/platform dependencies
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    testAnnotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.javax.annotation.api)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")

    implementation(project(":yappy-models:protobuf-models"))
    implementation(mn.grpc.services)
    implementation(mn.grpc.stub)
    implementation(mn.micronaut.http.client.core)
    implementation(mn.micronaut.protobuff.support)
    // https://mvnrepository.com/artifact/org.apache.tika/tika-core
    implementation("org.apache.tika:tika-core:3.1.0")
    // https://mvnrepository.com/artifact/org.apache.tika/tika-parsers
    implementation("org.apache.tika:tika-parsers:3.1.0")
    // https://mvnrepository.com/artifact/org.apache.tika/tika-parsers-standard-package
    implementation("org.apache.tika:tika-parsers-standard-package:3.1.0")
    testImplementation(mn.junit.jupiter.params)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation(project(":yappy-models:protobuf-models-test-data-resources"))
}


application {
    mainClass = "com.krickert.yappy.modules.tikaparser.TikaParserApplication"
}
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}


// graalvmNative.toolchainDetection = false

sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.modules.tikaparser.*")
    }
    testResources {
        sharedServer = true
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar> {
    isZip64 = true
    archiveBaseName.set("tika-parser")
    archiveClassifier.set("")
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
