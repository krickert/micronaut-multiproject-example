plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
}

version = "0.1"
group = "com.krickert.search.pipeline"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("info.picocli:picocli-codegen")
    implementation("info.picocli:picocli")
    implementation("io.micrometer:context-propagation")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut.aws:micronaut-aws-cloudwatch-logging")
    implementation("io.micronaut.aws:micronaut-aws-sdk-v2")
    implementation("io.micronaut.kafka:micronaut-kafka")
    implementation("io.micronaut.picocli:micronaut-picocli")
    implementation("io.micronaut.reactor:micronaut-reactor")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.platform:junit-platform-suite-engine")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:testcontainers")
}


application {
    mainClass = "com.krickert.search.pipeline.CliCommand"
}
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}



micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search.pipeline.*")
    }
    testResources {
        sharedServer = true
    }
}


tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}


