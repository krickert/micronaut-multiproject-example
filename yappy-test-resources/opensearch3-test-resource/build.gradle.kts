dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))

    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")

    api(mn.logback.classic)


}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Yappy Test Resources - OpenSearch 3.0")
                description.set("Test resource providers for Yappy platform")
            }
        }
    }
}
