dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))

    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")
    // No specific OpenSearch TestContainer dependency needed as we're implementing it ourselves

    api(mn.logback.classic)

    // JSON processing
    implementation("io.micronaut.serde:micronaut-serde-jackson")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.assertj:assertj-core")
    testAnnotationProcessor("io.micronaut:micronaut-inject-java")
    testImplementation("io.micronaut.serde:micronaut-serde-jackson")
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resources - OpenSearch 3")
                description.set("Test resource providers for Yappy platform")
            }
        }
    }
}
