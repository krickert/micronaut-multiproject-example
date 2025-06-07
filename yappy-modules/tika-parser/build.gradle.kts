plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("io.micronaut.test-resources") version "4.5.3"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.tikaparser"

repositories {
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
}


application {
    mainClass = "com.krickert.yappy.modules.tikaparser.Application"
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
