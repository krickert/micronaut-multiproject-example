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
            
            // Disable javadoc for now
            tasks.withType<Javadoc>().configureEach {
                isEnabled = false
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
        ":yappy-modules:embedder:dockerBuild",
        ":yappy-modules:echo:dockerBuild",
        ":yappy-modules:test-module:dockerBuild"
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
        ":yappy-modules:embedder:dockerBuild",
        ":yappy-modules:echo:dockerBuild",
        ":yappy-modules:test-module:dockerBuild"
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
                   echo:latest echo:1.0.0-SNAPSHOT \
                   test-module:latest test-module:1.0.0-SNAPSHOT \
                   yappy-orchestrator:latest yappy-orchestrator:1.0.0-SNAPSHOT \
        2>/dev/null || true
    """.trimIndent())
}

tasks.register("cleanTestResources") {
    group = "verification"
    description = "Clean all test resources and reset test configuration"
    
    doLast {
        println("🧹 Cleaning test resources...")
        
        // Clean gradle caches related to test resources
        delete(fileTree(".gradle/test-resources"))
        
        // Clean all build directories
        subprojects {
            delete("$projectDir/build")
            delete("$projectDir/.gradle")
        }
        delete("build")
        delete(".gradle")
        
        // Kill any running test containers
        exec {
            commandLine("sh", "-c", "docker ps -q --filter label=org.testcontainers=true | xargs -r docker stop")
            isIgnoreExitValue = true
        }
        
        exec {
            commandLine("sh", "-c", "docker ps -aq --filter label=org.testcontainers=true | xargs -r docker rm")
            isIgnoreExitValue = true
        }
        
        // Clean micronaut test resources server processes
        exec {
            commandLine("sh", "-c", "pkill -f 'micronaut.testresources' || true")
            isIgnoreExitValue = true
        }
        
        // Clean testcontainers networks
        exec {
            commandLine("sh", "-c", "docker network ls -q --filter name=testcontainers | xargs -r docker network rm")
            isIgnoreExitValue = true
        }
        
        println("✅ Test resources cleaned!")
        println("")
        println("💡 To fully reset, run:")
        println("   ./gradlew resetTestEnvironment")
        println("   ./gradlew test")
    }
}

tasks.register("resetTestEnvironment") {
    group = "verification"
    description = "Full reset of test environment including containers and resources"
    
    dependsOn("clean", "cleanTestResources")
    
    doLast {
        println("")
        println("🎉 Test environment reset complete!")
        println("🚀 You can now run tests with: ./gradlew test")
    }
}

tasks.register("fixConsulConfig") {
    group = "verification"  
    description = "Fix the recurring consul.client.host configuration issue"
    
    doLast {
        println("🔧 Fixing Consul configuration...")
        
        // Update test-resources.properties with proper consul config
        val testResourcesFile = file("yappy-orchestrator/engine-core/src/test/resources/test-resources.properties")
        if (testResourcesFile.exists()) {
            val content = testResourcesFile.readText()
            if (!content.contains("consul.client.host")) {
                testResourcesFile.appendText("""

# Consul test configuration - auto-generated
consul.client.host=localhost
consul.client.port=8500
consul.client.default-zone=test
consul.discovery.host=localhost
consul.discovery.port=8500
""")
                println("✅ Added Consul configuration to test-resources.properties")
            } else {
                println("✅ Consul configuration already present")
            }
        }
        
        println("🎯 Consul configuration fix applied!")
        println("💡 Run './gradlew cleanTestResources test' if issues persist")
    }
}
