plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.test-resources") version "4.5.3"
    id("io.micronaut.library") version "4.5.3"
}

allprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
        // Add other repositories if needed
    }

    // Apply plugins to all subprojects
    apply {
        plugin("java-library")
        plugin("maven-publish")
        plugin("io.micronaut.test-resources")
        plugin("io.micronaut.library")
    }

    // Configure Java for all subprojects
    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Configure Micronaut for all subprojects
    extensions.configure<io.micronaut.gradle.MicronautExtension> {
        version("4.8.2")
    }


}