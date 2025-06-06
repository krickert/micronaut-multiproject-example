plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.library") version "4.5.3"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withJavadocJar()
    withSourcesJar()
}

micronaut {
    version("4.8.2")
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))

    // Micronaut test resources
    api(mn.micronaut.test.resources.core)
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")

    // TestContainers core
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:consul")
    testImplementation("org.testcontainers:kafka")
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))


    // Logging
    api(mn.logback.classic)
    // Add dependencies for running tests WITHIN this module
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")

    // Testcontainers JUnit 5 extension is very helpful
    testImplementation("org.testcontainers:junit-jupiter")

}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resources - Engine Tika Parser")
                description.set("Test resource provider for Tika Parser engine")
            }
        }
    }
}