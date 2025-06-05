plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = rootProject.version
group = "com.krickert.yappy.containers"

repositories {
    mavenCentral()
}

// Configuration to fetch the module JAR
configurations {
    create("moduleArtifact")
}

dependencies {
    // Engine dependencies (inherit from engine project)
    implementation(project(":yappy-engine"))
    
    // Module artifact - reference the module project
    "moduleArtifact"(project(":yappy-modules:tika-parser"))
}

application {
    mainClass.set("com.krickert.search.pipeline.Application")
}

micronaut {
    runtime("netty")
    processing {
        incremental(true)
    }
}

// Task to copy module files into docker build directory
tasks.register<Copy>("copyModuleFiles") {
    dependsOn(":yappy-modules:tika-parser:shadowJar")
    
    // Find the shadow JAR from the tika-parser module
    val tikaProject = project(":yappy-modules:tika-parser")
    
    from(tikaProject.tasks.named("shadowJar", Jar::class.java).map { it.archiveFile })
    from("src/main/docker")
    
    into("build/docker/main/layers/modules")
    
    rename { filename ->
        when {
            filename.endsWith(".jar") -> "tika-parser.jar"
            else -> filename
        }
    }
}

// Configure Docker build after Dockerfile is generated
tasks.named("dockerfile") {
    dependsOn("copyModuleFiles")
    
    doLast {
        // Get the generated Dockerfile
        val dockerFile = file("build/docker/main/Dockerfile")
        val originalContent = dockerFile.readText()
        
        // Modify the Dockerfile
        val modifiedContent = originalContent
            .replace("FROM eclipse-temurin:21-jre", "FROM eclipse-temurin:21-jre-alpine")
            .replace("EXPOSE 8080", "EXPOSE 8080 50051 50053")
            .replace("ENTRYPOINT [\"java\", \"-jar\", \"/home/app/application.jar\"]", 
                     "ENTRYPOINT [\"/home/app/start.sh\"]")
        
        // Add our custom instructions before the EXPOSE line
        val customInstructions = """
# Install supervisord
RUN apk add --no-cache supervisor bash curl

# Create directories
RUN mkdir -p /var/log/supervisor /var/run

# Copy module JAR and config files
COPY --link layers/modules /home/app/modules

# Set up startup script
RUN echo '#!/bin/bash' > /home/app/start.sh && \
    echo 'exec /usr/bin/supervisord -c /home/app/modules/supervisord.conf' >> /home/app/start.sh && \
    chmod +x /home/app/start.sh

"""
        
        // Insert custom instructions before EXPOSE
        val finalContent = modifiedContent.replace("EXPOSE ", customInstructions + "EXPOSE ")
        
        // Write back
        dockerFile.writeText(finalContent)
    }
}

// Task to build Docker image
tasks.register("buildDockerImage") {
    group = "docker"
    description = "Build Docker image for Engine with Tika Parser"
    dependsOn("dockerBuild")
}