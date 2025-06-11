plugins {
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.Dockerfile

val dockerImageName = "echo"
val dockerImageVersion = "1.0.0-SNAPSHOT"

// Ensure the module is built first
val copyJar = tasks.register<Copy>("copyJar") {
    dependsOn(":yappy-modules:echo:shadowJar")
    from("../../yappy-modules/echo/build/libs/")
    include("*-all.jar")
    into("build/docker")
    rename { "app.jar" }
}

val createDockerfile = tasks.register<Dockerfile>("createDockerfile") {
    dependsOn(copyJar)
    destFile.set(file("build/docker/Dockerfile"))
    from("eclipse-temurin:21-jre")
    workingDir("/app")
    copyFile("app.jar", "/app/")
    exposePort(8080)
    entryPoint("java", "-jar", "/app/app.jar")
}

tasks.register<DockerBuildImage>("dockerBuild") {
    dependsOn(createDockerfile)
    inputDir.set(file("build/docker"))
    images.add("${dockerImageName}:${dockerImageVersion}")
    images.add("${dockerImageName}:latest")
}