// File: pipeline-service-test-utils/build.gradle.kts
plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(platform(project(":bom")))

    // Depend on core library
    api(project(":pipeline-service-core"))

    // Include testing libraries using mn catalog
    api(mn.micronaut.test.junit5)

    // May depend on other utils
    api(project(":util"))
}