plugins {
    `java-library`
    id("io.micronaut.library") version "4.5.3"
}

repositories {
    mavenCentral()
}

dependencies {
    // Annotation processors
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.inject.java)
    
    // Core dependencies - minimal to avoid circular dependencies
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.context)
    implementation(mn.micronaut.jackson.databind)
    compileOnly(mn.micronaut.validation)
    
    // Protobuf models - for PipeStream
    api(project(":yappy-models:protobuf-models"))
    
    // Utility dependencies
    compileOnly(mn.lombok)
    
    // Logging
    implementation(libs.slf4j.api)
    
    // Testing
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.bundles.testing.jvm)
    testImplementation(mn.assertj.core)
    testImplementation(mn.mockito.core)
    
    // Test runtime dependencies
    testRuntimeOnly(libs.logback.classic)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

micronaut {
    processing {
        incremental(true)
        annotations("com.krickert.search.commons.*")
    }
}