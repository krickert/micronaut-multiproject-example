plugins {
    `java-library`
    id("io.micronaut.test-resources") version "4.5.3"
}

dependencies {
    implementation(project(":yappy-test-resources:yappy-module-base-test-resource"))
    implementation("io.micronaut.testresources:micronaut-test-resources-testcontainers")
    implementation("org.testcontainers:testcontainers")
    implementation("org.slf4j:slf4j-api")
    
    // Engine needs access to infrastructure
    implementation(project(":yappy-test-resources:consul-test-resource"))
    implementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    implementation(project(":yappy-test-resources:apicurio-test-resource"))
    implementation(project(":yappy-test-resources:opensearch3-test-resource"))
    implementation(project(":yappy-test-resources:moto-test-resource"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}