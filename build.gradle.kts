import org.asciidoctor.gradle.jvm.AsciidoctorTask

plugins {
    id("org.asciidoctor.jvm.convert") version "4.0.4"
    id("com.bmuschko.docker-remote-api") version "9.4.0" apply false
    idea
}

repositories {
    mavenCentral()
}

asciidoctorj {
    modules {
        diagram.use()
        // optional: pin version
        diagram.version("2.3.2")
    }
    attributes(
        mapOf(
            "source-highlighter" to "coderay",
            "docinfo1" to "shared"
        )
    )
}
tasks.named<AsciidoctorTask>("asciidoctor") {
    setSourceDir(file("src/docs"))
    setOutputDir(file("docs"))
    logDocuments = true

    attributes(
        mapOf(
            // tell HTML where images “live”
            "imagesdir" to "images",
            // tell Diagram to also dump images here
            "imagesoutdir" to "docs/images",
            "plantuml-format" to "svg",
            "docinfodir" to "src/docs"
        )
    )
}


group = "com.krickert.search"
version = "1.0.0-SNAPSHOT"

subprojects {
    repositories {
        mavenCentral()
    }

    // Apply Java toolchain configuration to all subprojects with Java plugin
    plugins.withId("java-base") {
        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
        }

        // Configure JUnit 5 for all subprojects with Java capabilities
        afterEvaluate {
            tasks.withType<Test>().configureEach {
                useJUnitPlatform()
                testLogging {
                    events("passed", "skipped", "failed")
                }
            }
        }
    }

    // Apply publishing configuration defaults
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                mavenLocal()
            }
        }
    }
}

// Task to build all Docker images in the correct order
tasks.register("dockerBuildAll") {
    group = "docker"
    description = "Builds all Docker images for modules and orchestrator"
    
    // Build modules first
    dependsOn(
        ":yappy-modules:chunker:dockerBuild",
        ":yappy-modules:tika-parser:dockerBuild",
        ":yappy-modules:embedder:dockerBuild"
    )
    
    // Then build orchestrator
    finalizedBy(":yappy-orchestrator:dockerBuild")
}

// Task to just build module containers
tasks.register("dockerBuildModules") {
    group = "docker"
    description = "Builds Docker images for all modules"
    
    dependsOn(
        ":yappy-modules:chunker:dockerBuild",
        ":yappy-modules:tika-parser:dockerBuild",
        ":yappy-modules:embedder:dockerBuild"
    )
}

// Clean all Docker images
tasks.register<Exec>("dockerCleanAll") {
    group = "docker"
    description = "Removes all project Docker images"
    
    commandLine("bash", "-c", """
        docker rmi chunkerapplication:latest chunkerapplication:1.0.0-SNAPSHOT \
                   tika-parser:latest tika-parser:1.0.0-SNAPSHOT \
                   embedder:latest embedder:1.0.0-SNAPSHOT \
                   yappy-orchestrator:latest yappy-orchestrator:1.0.0-SNAPSHOT \
        2>/dev/null || true
    """.trimIndent())
}
