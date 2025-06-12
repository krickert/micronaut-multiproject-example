plugins {
    `java-library`
    `maven-publish`
    id("io.micronaut.test-resources") 
    id("io.micronaut.library")
}

micronaut {
    version("4.8.2")
    testResources {
        enabled.set(true)
    }
}

dependencies {
    implementation("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    implementation("org.testcontainers:testcontainers")
    implementation("io.grpc:grpc-stub")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-netty-shaded")
    implementation("io.grpc:grpc-services:1.62.2")
    implementation("javax.annotation:javax.annotation-api")
    implementation("org.slf4j:slf4j-api")
    
    // Export these dependencies for modules that use this
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}