
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar

plugins {
    id("io.micronaut.library") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
    id("application")
}

version = rootProject.version
group = "com.krickert.yappy.containers"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":yappy-engine"))
    implementation(project(":yappy-modules:tika-parser"))
}

application {
    mainClass.set("com.krickert.yappy.bootstrap.EngineApplication")
}

micronaut {
    processing {
        incremental(true)
    }
}

tasks.named<ShadowJar>("shadowJar") {
    dependsOn(":yappy-modules:tika-parser:shadowJar")
    isZip64 = true
    archiveBaseName.set("engine")
    archiveClassifier.set("")
}

// This task now creates the complete, correct build context
tasks.register<Copy>("prepareDockerContext") {
    dependsOn(tasks.shadowJar)

    // Copy the Dockerfile to the root of the build context
    from("src/main/docker/Dockerfile")

    // --- All subsequent copies go into the 'layers' sub-directory ---
    with(copySpec().into("layers")) {
        from(tasks.shadowJar.get().outputs.files) {
            rename { "engine.jar" }
            into("engine")
        }
        from(project(":yappy-modules:tika-parser").tasks.named("shadowJar", Jar::class.java)) {
            rename { "tika-parser.jar" }
            into("modules")
        }
        from("src/main/docker") {
            include("start.sh", "supervisord.conf")
            into("config")
        }
        from("src/main/resources") {
            include("application.yml", "module-application.yml")
            into("config")
        }
    }

    // The final destination for the context
    into("build/docker-context")
}

// The dockerBuild task is now extremely simple
tasks.register<Exec>("dockerBuild") {
    group = "build"
    description = "Builds the custom 2-JVM Docker container."
    dependsOn("prepareDockerContext")
    workingDir = file("build/docker-context")
    commandLine("docker", "build", "-t", "engine-tika-parser:${project.version}", ".")
}