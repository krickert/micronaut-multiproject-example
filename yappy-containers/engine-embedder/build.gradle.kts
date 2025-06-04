import com.bmuschko.gradle.docker.tasks.image.DockerBuildImage
import com.bmuschko.gradle.docker.tasks.image.DockerPushImage

plugins {
    id("com.bmuschko.docker-remote-api") version "9.4.0"
}

// This module is just for Docker packaging
// The actual building happens on the host

val dockerRegistry: String by project
val dockerNamespace: String by project

docker {
    registryCredentials {
        url.set(dockerRegistry)
        username.set(System.getenv("DOCKER_USERNAME"))
        password.set(System.getenv("DOCKER_PASSWORD"))
    }
}

tasks.register<Copy>("prepareDockerContext") {
    group = "docker"
    description = "Prepare Docker build context"
    
    from("${projectDir}/Dockerfile")
    from("${projectDir}/src/main/resources") {
        into("engine-embedder/src/main/resources")
    }
    
    into("${buildDir}/docker")
}

tasks.register<DockerBuildImage>("dockerBuild") {
    group = "docker"
    description = "Build Docker image for engine-embedder"
    dependsOn("prepareDockerContext")
    
    inputDir.set(rootProject.projectDir) // Build from root to access all modules
    dockerFile.set(file("${buildDir}/docker/Dockerfile"))
    images.add("${dockerRegistry}/${dockerNamespace}/engine-embedder:${version}")
    images.add("${dockerRegistry}/${dockerNamespace}/engine-embedder:latest")
    
    // Build args for flexibility
    buildArgs.put("ENGINE_VERSION", version.toString())
    buildArgs.put("MODULE_VERSION", version.toString())
}

tasks.register<DockerPushImage>("dockerPush") {
    group = "docker"
    description = "Push Docker image to registry"
    dependsOn("dockerBuild")
    
    images.add("${dockerRegistry}/${dockerNamespace}/engine-embedder:${version}")
    images.add("${dockerRegistry}/${dockerNamespace}/engine-embedder:latest")
}