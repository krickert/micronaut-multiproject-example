// File: pipeline-service-core/build.gradle.kts
plugins {
    `java-library`
    alias(libs.plugins.micronaut.library)
}

group = rootProject.group
version = rootProject.version

micronaut {
    version("4.8.2")
    processing {
        incremental(true)
        annotations("com.krickert.search.pipeline.core.*")
    }
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))

    // Micronaut dependencies using mn catalog
    annotationProcessor(mn.micronaut.inject.java)
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.runtime)

    // Project dependencies
    api(project(":protobuf-models"))
    api(project(":util"))

    // Other dependencies
    implementation(libs.slf4j.api)

    // Testing dependencies
    testImplementation(mn.micronaut.test.junit5)
    testAnnotationProcessor(mn.micronaut.inject.java)
}