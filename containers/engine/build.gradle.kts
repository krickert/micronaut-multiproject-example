plugins {
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

// Configure Docker tasks
tasks.register<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuild") {
    inputDir.set(file("."))
    images.set(listOf(
        "yappy-engine:latest",
        "yappy-engine:1.0.0-SNAPSHOT"
    ))
    
    // Ensure orchestrator JAR is built first
    dependsOn(":yappy-orchestrator:shadowJar")
}

tasks.register("build") {
    dependsOn("dockerBuild")
}