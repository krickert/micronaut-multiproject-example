plugins {
    id("java-library")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    
    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")
    
    // For gRPC health checks
    api("io.grpc:grpc-stub")
    api("io.grpc:grpc-protobuf")
    api("io.grpc:grpc-services")
    api("javax.annotation:javax.annotation-api")
    
    implementation("org.slf4j:slf4j-api")
    implementation("commons-io:commons-io:2.15.1")
    
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly(mn.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}