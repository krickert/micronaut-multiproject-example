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
}
tasks.named<AsciidoctorTask>("asciidoctor") {
    setSourceDir(file("docsSrc"))
    setOutputDir(file("docs"))

    attributes(
        mapOf(
            // tell HTML where images “live”
            "imagesdir"      to "images",
            // tell Diagram to also dump images here
            "imagesoutdir"   to "docs/images",
            "plantuml-format" to "svg"
        )
    )
}


group = "com.yourcompany.pipeline"
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

