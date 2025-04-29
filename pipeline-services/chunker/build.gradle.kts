// File: pipeline-services/chunker/build.gradle.kts
plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.micronaut.library)
}

group = rootProject.group
version = rootProject.version

java {
    withJavadocJar()
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

micronaut {
    version("4.8.2")
    processing {
        incremental(true)
        annotations("com.krickert.search.chunker.*")
    }
}

dependencies {
    //BOM
    implementation(platform(project(":bom")))
    annotationProcessor(platform(project(":bom")))
    testImplementation(platform(project(":bom")))
    testAnnotationProcessor(platform(project(":bom")))
    // Micronaut dependencies using mn catalog
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.lombok)
    annotationProcessor(mn.micronaut.validation)
    // Project dependencies
    implementation(project(":pipeline-service-core"))
    // https://mvnrepository.com/artifact/com.google.guava/guava
    implementation(libs.guava)
    compileOnly(mn.lombok)
    // Testing dependencies
    testImplementation(mn.micronaut.test.junit5)
    testImplementation(mn.micronaut.http.client)
    testImplementation(mn.micronaut.http.server.netty)
    testImplementation(project(":pipeline-service-test-utils:micronaut-test-consul-container"))
    testImplementation(project(":pipeline-service-test-utils:pipeline-test-platform"))
    testAnnotationProcessor(mn.micronaut.inject.java)
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Pipeline Chunker Service")
                description.set("Chunker service for pipeline implementation")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
            }
        }
    }
}