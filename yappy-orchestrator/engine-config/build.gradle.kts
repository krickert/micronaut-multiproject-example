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
    annotationProcessor(mn.micronaut.validation.processor)
    
    // Core dependencies
    implementation(project(":yappy-orchestrator:engine-core"))
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    
    // Configuration management
    implementation(project(":yappy-consul-config"))
    implementation(mn.micronaut.discovery.client)
    implementation(mn.micronaut.runtime)
    
    // Validation
    implementation(mn.micronaut.validation)
    
    // Utilities
    compileOnly(mn.lombok)
    implementation(libs.slf4j.api)
    
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
        annotations("com.krickert.search.engine.config.*")
    }
}