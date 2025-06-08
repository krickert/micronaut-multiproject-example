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
    
    // Reactive support
    implementation(mn.micronaut.reactor)
    
    // gRPC for pipeline step communication
    implementation(mn.micronaut.grpc.runtime)
    implementation(libs.grpc.stub)
    
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
        annotations("com.krickert.search.pipeline.*")
    }
}