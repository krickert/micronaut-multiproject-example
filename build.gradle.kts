import org.asciidoctor.gradle.jvm.AsciidoctorTask

plugins {
    id("org.asciidoctor.jvm.convert") version "4.0.4"
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

tasks.register("dockerBuild") {
    group = "Build"
    description = "Builds the main engine-tika-parser Docker image."
    // This tells the root 'dockerBuild' task to run the specific module's task.
    dependsOn(":yappy-containers:engine-tika-parser:dockerBuild")
}

