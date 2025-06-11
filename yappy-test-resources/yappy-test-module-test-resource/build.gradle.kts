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
    
    // Base module test resource
    api(project(":yappy-test-resources:yappy-module-base-test-resource"))
    
    // Kafka test resource dependency for coordination
    implementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    
    // Apicurio test resource dependency for schema registry
    implementation(project(":yappy-test-resources:apicurio-test-resource"))
    
    api("io.micronaut.testresources:micronaut-test-resources-core")
    api("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    api("org.testcontainers:testcontainers")
    
    implementation("org.slf4j:slf4j-api")
    
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly(mn.logback.classic)
    testRuntimeOnly(mn.micronaut.jackson.databind)
}

tasks.test {
    useJUnitPlatform()
}