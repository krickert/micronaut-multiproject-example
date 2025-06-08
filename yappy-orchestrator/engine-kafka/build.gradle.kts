plugins {
    id("io.micronaut.library")
}

repositories {
    mavenCentral()
}

dependencies {
    // Annotation processors
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.inject.java)
    
    // Core dependencies
    implementation(project(":yappy-orchestrator:engine-core"))
    implementation(project(":yappy-orchestrator:engine-pipeline"))
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    
    // Kafka dependencies
    implementation(mn.micronaut.kafka)
    implementation(mn.kafka.clients)
    implementation(mn.kafka.streams)
    
    // Kafka slot manager integration
    implementation(project(":yappy-kafka-slot-manager"))
    
    // Schema registry
    implementation(libs.apicurio.serde)
    
    // Utilities
    compileOnly(mn.lombok)
    implementation(libs.slf4j.api)
    implementation(libs.guava)
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.bundles.testing.jvm)
    testImplementation(mn.assertj.core)
    testImplementation(mn.mockito.core)
    testImplementation("org.testcontainers:kafka")
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    processing {
        incremental(true)
        annotations("com.krickert.search.engine.kafka.*")
    }
}