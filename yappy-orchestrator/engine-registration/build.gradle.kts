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
    annotationProcessor("io.micronaut.grpc:micronaut-grpc-annotation")
    
    // Core dependencies
    implementation(project(":yappy-orchestrator:engine-core"))
    implementation(project(":yappy-orchestrator:engine-health"))
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    
    // gRPC dependencies
    implementation(mn.micronaut.grpc.runtime)
    implementation(libs.grpc.stub)
    implementation(libs.grpc.protobuf)
    
    // Service discovery
    implementation(mn.micronaut.discovery.client)
    
    // Utilities
    compileOnly(mn.lombok)
    implementation(libs.slf4j.api)
    implementation(libs.guava)
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.bundles.testing.jvm)
    testImplementation(mn.assertj.core)
    testImplementation(mn.mockito.core)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

micronaut {
    processing {
        incremental(true)
        annotations("com.krickert.search.engine.registration.*")
    }
}