// File: bom/build.gradle.kts
plugins {
    `java-platform`
    `maven-publish`
}

group = rootProject.group
version = rootProject.version

javaPlatform {
    // allowDependencies()
}

dependencies {
    constraints {
        // Import Micronaut Platform BOM (provides JUnit constraints etc.)
        // For BOM imports in constraints, use the specific notation:
        api(libs.micronaut.platform)  // This is the correct way to reference the BOM

        // Add annotation processors from Micronaut
        api("io.micronaut:micronaut-inject-java:4.8.2")
        api("org.projectlombok:lombok:1.18.30")
        api("io.micronaut.validation:micronaut-validation-processor:4.8.2")

        // Explicitly add JUnit with version
        api("org.junit.jupiter:junit-jupiter-api:5.10.0")
        api("org.junit.jupiter:junit-jupiter-engine:5.10.0")

        // Other constraints remain the same
        api(libs.protobuf.java)
        api(libs.grpc.stub)
        api(libs.grpc.protobuf)
        api(libs.guava)
        api(libs.jackson.databind)
        api(libs.commons.lang3)
        api(libs.slf4j.api)
        api(libs.logback.classic)

        // Add constraint for Wire library to fix dependency conflict
        api("com.squareup.wire:wire-schema:4.9.3")
        api("com.squareup.wire:wire-runtime:4.9.3")

        // Constrain own modules
        api("${rootProject.group}:protobuf-models:${rootProject.version}")
        api("${rootProject.group}:util:${rootProject.version}")
        api("${rootProject.group}:pipeline-service-core:${rootProject.version}")
        api("${rootProject.group}:pipeline-service-test-utils:${rootProject.version}")
        // Kafka registry modules
        api("${rootProject.group}:pipeline-service-test-utils.micronaut-kafka-registry-core:${rootProject.version}")
        api("${rootProject.group}:pipeline-service-test-utils.micronaut-kafka-registry-moto:${rootProject.version}")
        api("${rootProject.group}:pipeline-service-test-utils.micronaut-kafka-registry-apicurio:${rootProject.version}")
        // Yappy models modules
        api("${rootProject.group}:yappy-models:${rootProject.version}")
        api("${rootProject.group}:pipeline-config-models:${rootProject.version}")
        api("${rootProject.group}:schema-registry-models:${rootProject.version}")
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJavaPlatform") {
            from(components["javaPlatform"])
            groupId = project.group.toString()
            artifactId = project.name
            version = project.version.toString()
            pom {
                name.set("My Pipeline System BOM")
                description.set("Bill of Materials for My Pipeline System components")
            }
        }
    }
}
