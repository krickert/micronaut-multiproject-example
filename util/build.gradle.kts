// File: util/build.gradle.kts
plugins {
    `java-library`
}

group = rootProject.group
version = rootProject.version

dependencies {
    implementation(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    implementation(mn.protobuf.java.util)
    api(libs.guava) // Expose Guava via API
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Testing dependencies
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}
