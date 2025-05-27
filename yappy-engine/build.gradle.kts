import io.micronaut.testresources.buildtools.KnownModules

plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
    id("io.micronaut.aot") version "4.5.3"
}

group = rootProject.group
version = rootProject.version
application {
    mainClass = "com.krickert.yappy.engine.YappyEngineApplication"
}

graalvmNative.toolchainDetection = false

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    runtime("netty")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.search..**", "com.krickert.testcontainers.consul.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(false)
    }
    aot {
        // Please review carefully the optimizations enabled below
        // Check https://micronaut-projects.github.io/micronaut-aot/latest/guide/ for more details
        optimizeServiceLoading = false
        convertYamlToJava = false
        precomputeOperations = true
        cacheEnvironment = true
        optimizeClassLoading = true
        deduceEnvironment = true
        optimizeNetty = true
        replaceLogbackXml = true
    }
}

// Helper function to check if DEV environment is enabled
fun isDevEnvironmentEnabled(): Boolean {
    // Check system property
    val devFromSysProp = System.getProperty("micronaut.environments")?.contains("dev") ?: false
    // Check environment variable (as fallback)
    val devFromEnv = System.getenv("MICRONAUT_ENVIRONMENTS")?.contains("dev") ?: false
    // Check Gradle project property if set
    val devFromProject = project.findProperty("micronautEnv")?.toString()?.contains("dev") ?: false

    return devFromSysProp || devFromEnv || devFromProject
}

dependencies {
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("io.micronaut:micronaut-http-validation")
    annotationProcessor("io.micronaut.jsonschema:micronaut-json-schema-processor")
    annotationProcessor("io.micronaut.openapi:micronaut-openapi")
    annotationProcessor("io.micronaut.serde:micronaut-serde-processor")
    implementation("io.micrometer:context-propagation")
    implementation("io.micronaut:micronaut-discovery-core")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-management")
    implementation("io.micronaut:micronaut-retry")
    implementation("io.micronaut.discovery:micronaut-discovery-client")
    implementation("io.micronaut.jsonschema:micronaut-json-schema-annotations")
    implementation("io.micronaut.micrometer:micronaut-micrometer-core")
    implementation("io.micronaut.micrometer:micronaut-micrometer-observation")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-jmx")
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")
    implementation("io.micronaut.serde:micronaut-serde-jackson")
    implementation("io.micronaut.views:micronaut-views-fieldset")
    implementation("io.micronaut.views:micronaut-views-thymeleaf")
    compileOnly("io.micronaut.openapi:micronaut-openapi-annotations")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("ch.qos.logback:logback-classic")
    runtimeOnly("org.yaml:snakeyaml")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility:4.3.0")
    testImplementation("org.junit.jupiter:junit-jupiter-params")
    testImplementation("org.junit.platform:junit-platform-suite-engine")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")
    developmentOnly("io.micronaut.controlpanel:micronaut-control-panel-management")
    developmentOnly("io.micronaut.controlpanel:micronaut-control-panel-ui")

    // Apply BOM/platform dependencies
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Annotation processors
    annotationProcessor(libs.bundles.micronaut.annotation.processors)
    annotationProcessor(mn.lombok)
    compileOnly(mn.lombok)

    // Micrometer dependencies for metrics
    implementation("io.micronaut.micrometer:micronaut-micrometer-core:5.5.0")
    implementation("io.micronaut.micrometer:micronaut-micrometer-registry-prometheus:5.5.0")

    // Jackson JSR310 module for Java 8 date/time types
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")

    api(project(":yappy-consul-config"))

    runtimeOnly(mn.logback.classic) // This line was missing from your provided snippet, re-add if it was there
    implementation(mn.micronaut.reactor.http.client)
    implementation(mn.javax.annotation.api)
    implementation(mn.micronaut.context)

    implementation(mn.micronaut.grpc.annotation)
    implementation(mn.grpc.services)
    implementation(mn.micronaut.protobuff.support)
    implementation(mn.micronaut.grpc.server.runtime)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.context)
    implementation(mn.netty.common)
// https://mvnrepository.com/artifact/io.grpc/grpc-core
    implementation("io.grpc:grpc-core:1.72.0")

    implementation(project(":yappy-models:protobuf-models"))

    testImplementation(project(":yappy-test-resources:consul-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:consul-test-resource"))
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testImplementation(project(":yappy-test-resources:moto-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:moto-test-resource"))
    testImplementation(project(":yappy-modules:echo"))
    testImplementation(project(":yappy-modules:chunker"))
    runtimeOnly("io.micronaut.discovery:micronaut-discovery-client")


    // Apicurio Registry
    // https://mvnrepository.com/artifact/io.apicurio/apicurio-registry-protobuf-serde-kafka
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.6")


    testImplementation(mn.reactor.test)
    testImplementation(mn.assertj.core)
    runtimeOnly("io.micronaut.discovery:micronaut-discovery-client")
    testImplementation(mn.micronaut.http.client.core)
    testImplementation(mn.micronaut.grpc.client.runtime)
    // https://mvnrepository.com/artifact/com.networknt/json-schema-validator
    implementation("com.networknt:json-schema-validator:1.5.6")
    // https://mvnrepository.com/artifact/org.jgrapht/jgrapht-core
    implementation("org.jgrapht:jgrapht-core:1.5.2")

    implementation(mn.micronaut.kafka) {
        exclude("org.testcontainers", "testcontainers-kafka")
    }
    implementation(mn.protobuf.java.util)
    implementation(mn.grpc.protobuf)
    implementation(mn.micronaut.grpc.runtime)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)

    //AWS Dependencies
    //AWS SDK dependencies
    api(mn.micronaut.aws.sdk.v2)
    // AWS Glue Schema Registry dependencies
    api(libs.amazon.glue) {
        // Exclude transitive Wire dependencies to avoid conflicts
        exclude(group = "com.squareup.wire")
    }
    api(libs.amazon.msk.iam)
    api(libs.amazon.connection.client)
    testImplementation(mn.junit.jupiter.api)
    testImplementation(mn.junit.jupiter.engine)
    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-params
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
    testImplementation(mn.mockito.junit.jupiter)
    // https://mvnrepository.com/artifact/org.awaitility/awaitility
    testImplementation("org.awaitility:awaitility:4.3.0")
    implementation(mn.micronaut.views.thymeleaf)
//    developmentOnly("io.micronaut.controlpanel:micronaut-control-panel-ui")
//    developmentOnly("io.micronaut.controlpanel:micronaut-control-panel-management")


}

//// Add this block to explicitly configure the Mockito agent
//tasks.withType<Test>().configureEach {
//    val mockitoCoreJar = configurations.testRuntimeClasspath.get()
//        .files.find { it.name.startsWith("mockito-core") }
//    if (mockitoCoreJar != null) {
//        jvmArgs("-javaagent:${mockitoCoreJar.absolutePath}")
//        logger.lifecycle("Configured Mockito agent: ${mockitoCoreJar.absolutePath}")
//    } else {
//        logger.warn("WARNING: mockito-core.jar not found in testRuntimeClasspath for agent configuration. Mockito inline mocking may not work as expected or show warnings.")
//    }
//}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Consul Configuration Service")
                description.set("Centralized configuration service using Consul KV store")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}

tasks.named<io.micronaut.gradle.docker.NativeImageDockerfile>("dockerfileNative") {
    jdkVersion = "21"
}