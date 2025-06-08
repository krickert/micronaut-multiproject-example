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
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    implementation(mn.micronaut.management)
    
    // HTTP client for health checks
    implementation(mn.micronaut.http.client)
    
    // gRPC for health checks
    implementation(mn.micronaut.grpc.runtime)
    
    // Metrics and monitoring
    implementation(mn.micronaut.micrometer.core)
    
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
        annotations("com.krickert.search.engine.health.*")
    }
}