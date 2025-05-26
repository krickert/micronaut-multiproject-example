plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
}

group = "com.krickert.yappy.modules.wikicrawlerconnector"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

java {
    toolchain { // ADDED toolchain configuration
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.toVersion("21") // Keep existing from user baseline
    targetCompatibility = JavaVersion.toVersion("21") // Keep existing from user baseline
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("com.krickert.yappy.wikicrawler.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
    }
}

dependencies {
    // User-provided dependencies - ensure these are exactly as they gave
    annotationProcessor("io.micronaut.openapi:micronaut-openapi") // User provided
    implementation("io.micronaut.openapi:micronaut-openapi-annotations") // User provided

    // Assuming mn. and libs. resolve correctly from the project's version catalog
    annotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.inject)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.micronaut.http.client)
    implementation(mn.micronaut.management)
    implementation(mn.micronaut.grpc.runtime)

    implementation(project(":yappy-models:protobuf-models"))
    implementation("info.bliki.wiki:bliki-core:3.1.0")
    implementation(mn.micronaut.kafka)
    implementation(libs.apicurio.serde)
    implementation("com.squareup.wire:wire-runtime:5.2.0")

    implementation(libs.slf4j.api)
    runtimeOnly(mn.logback.classic)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(mn.micronaut.test.junit5)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testImplementation(mn.mockito.core)
    testImplementation(mn.assertj.core)

    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))

    // Add dependency on the connector test server for testing
    testImplementation(project(":yappy-modules:yappy-connector-test-server"))

    implementation("io.micronaut.reactor:micronaut-reactor") // User provided
    implementation("io.micronaut.reactor:micronaut-reactor-http-client") // User provided
    runtimeOnly("io.micronaut.openapi:micronaut-openapi:6.15.0") // User provided
    // https://mvnrepository.com/artifact/org.wikiclean/wikiclean
    implementation("org.wikiclean:wikiclean:1.2") {
        exclude("org.apache.commons", "commons-compress")
        exclude("org.apache.httpcomponents", "httpclient")
        exclude("org.apache.lucene", "lucene-queryparser")
        exclude("org.apache.lucene", "lucene-analyzers-common")
        exclude("org.apache.lucene", "lucene-core")
    }
    implementation("org.apache.httpcomponents:httpclient:4.5.13")
    // https://mvnrepository.com/artifact/edu.stanford.nlp/stanford-corenlp
    implementation("edu.stanford.nlp:stanford-corenlp:4.5.9") {
        exclude("org.apache.lucene", "lucene-queryparser")
        exclude("org.apache.lucene", "lucene-analyzers-common")
        exclude("org.apache.lucene", "lucene-core")
    }
    //TRANSITIVE DEPENDENCIES
    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.19.0")
    // https://mvnrepository.com/artifact/org.apache.commons/commons-compress
    implementation("org.apache.commons:commons-compress:1.27.1")
}

application {
    mainClass = "com.krickert.yappy.wikicrawler.WikiCrawlerApplication"
}

sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

application { // From user baseline
    mainClass = "com.krickert.yappy.wikicrawler.WikiCrawlerApplication"
}

// Note: The shadowJar task configuration from my original file is not in the user's baseline.
// If shading is needed, it would have to be re-added. For now, sticking to user's baseline + specified additions.
