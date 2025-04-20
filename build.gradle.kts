// build.gradle.kts (Root project)
plugins {
    id("org.asciidoctor.jvm.convert") version "4.0.4"
    base
}
buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("org.asciidoctor:asciidoctor-gradle-jvm:4.0.4")
    }
}

tasks.named<org.asciidoctor.gradle.jvm.AsciidoctorTask>("asciidoctor") {
    setSourceDir(file("docs"))
    setOutputDir(file("./build/docs"))
    baseDirFollowsSourceFile()
    // Any attributes needed by your documentation
    attributes(
        mapOf(
            "source-highlighter" to "highlight.js",
            "toc" to "left",
            "toclevels" to "3"
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