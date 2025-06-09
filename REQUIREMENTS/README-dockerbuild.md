# Docker Build Configuration for Multi-Module Projects

## Overview

This document describes the Docker build configuration for the Yappy platform's multi-module Gradle project, the issues encountered with the Micronaut Docker plugin, and the solutions implemented.

## The Problem

### Issue Description

When attempting to build Docker images in a multi-module Micronaut project, the `dockerBuild` task fails with connection errors:

```
java.lang.RuntimeException: java.io.IOException: Connection reset by peer
    at com.github.dockerjava.httpclient5.ApacheDockerHttpClientImpl.execute(...)
```

### Root Causes

1. **Large Build Contexts**: Multi-module projects have significantly larger build contexts due to:
   - Multiple dependency JARs in the `libs` layer
   - Project-specific JARs in the `project_libs` layer
   - Extensive resource files

2. **HTTP Client Limitations**: The Docker plugin uses an HTTP client to communicate with the Docker daemon, which struggles with larger payloads:
   - Connection resets during large file transfers
   - Timeout issues with bigger build contexts
   - Buffer overflow problems

3. **Module Dependencies**: Complex projects have internal dependencies that increase the build context size:
   - `please-work` (simple project): ~299MB Docker image, 1KB project_libs
   - `chunker` (multi-module): ~317MB Docker image, 1.2MB+ project_libs
   - `yappy-orchestrator`: ~465MB Docker image, multiple project dependencies

## The Solution

### 1. Add Docker Plugin Explicitly

The Micronaut Application plugin includes Docker support, but we need to add the Docker plugin explicitly for better control:

```kotlin
plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.bmuschko.docker-remote-api") version "9.4.0"
    // ... other plugins
}
```

### 2. Configure Docker Connection

Add Docker configuration to handle larger contexts:

```kotlin
// Configure Docker build to handle larger contexts
docker {
    // Use environment variable or default socket
    url = System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock"
    
    // API version compatibility
    apiVersion = "1.41"
}
```

### 3. Configure Docker Build Task

Properly configure the `dockerBuild` task with image naming:

```kotlin
// Configure the dockerBuild task
tasks.named<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuild") {
    val imageName = project.name.lowercase()
    images.set(listOf(
        "${imageName}:${project.version}",
        "${imageName}:latest"
    ))
}
```

### 4. Set Up Build Dependencies

For the orchestrator that depends on module containers:

```kotlin
// In yappy-orchestrator/build.gradle.kts
tasks.named<com.bmuschko.gradle.docker.tasks.image.DockerBuildImage>("dockerBuild") {
    // ... image configuration ...
    
    // Ensure module containers are built first
    dependsOn(
        ":yappy-modules:chunker:dockerBuild",
        ":yappy-modules:tika-parser:dockerBuild"
    )
}
```

### 5. Create Convenience Tasks

In the root `build.gradle.kts`:

```kotlin
// Task to build all Docker images in the correct order
tasks.register("dockerBuildAll") {
    group = "docker"
    description = "Builds all Docker images for modules and orchestrator"
    
    // Build modules first
    dependsOn(
        ":yappy-modules:chunker:dockerBuild",
        ":yappy-modules:tika-parser:dockerBuild"
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
        ":yappy-modules:tika-parser:dockerBuild"
    )
}

// Clean all Docker images
tasks.register<Exec>("dockerCleanAll") {
    group = "docker"
    description = "Removes all project Docker images"
    
    commandLine("bash", "-c", """
        docker rmi chunkerapplication:latest chunkerapplication:1.0.0-SNAPSHOT \
                   tika-parser:latest tika-parser:1.0.0-SNAPSHOT \
                   yappy-orchestrator:latest yappy-orchestrator:1.0.0-SNAPSHOT \
        2>/dev/null || true
    """.trimIndent())
}
```

## Why This is an Issue

### 1. Micronaut's Design Philosophy

The Micronaut team likely designed the Docker plugin for simpler, single-module applications. The plugin works perfectly for standalone projects but struggles with enterprise-scale multi-module builds.

### 2. Docker Build Context Limitations

Docker builds require sending the entire build context to the Docker daemon. In multi-module projects:
- Each module has its own dependencies
- Project libraries are copied into `project_libs`
- The total context size can exceed HTTP client buffer limits

### 3. Gradle Configuration Cache

The error messages also indicate issues with Gradle's configuration cache when using runtime project references in Docker tasks. This is a known limitation when mixing imperative code with declarative task configuration.

## Usage

### Building All Images

```bash
# Build all Docker images (modules first, then orchestrator)
./gradlew dockerBuildAll

# Build only module containers
./gradlew dockerBuildModules

# Build a specific module
./gradlew :yappy-modules:chunker:dockerBuild

# Clean all project Docker images
./gradlew dockerCleanAll
```

### Build Order

1. Module containers are built first (chunker, tika-parser)
2. Orchestrator is built last (can reference module containers)
3. All images are tagged with version and "latest"

## Alternative Approaches (Not Recommended)

### 1. Workaround with CLI

Some projects work around the issue by bypassing the plugin entirely:

```kotlin
tasks.register<Exec>("dockerBuildWorkaround") {
    workingDir = file("build/docker/main")
    commandLine("docker", "build", "-t", "myimage:latest", ".")
}
```

**Downsides**: Loses all Micronaut Docker plugin features like layer caching, proper tagging, and push capabilities.

### 2. Separate Container Projects

Creating independent projects in a `containers/` folder with minimal builds.

**Downsides**: Duplicates build logic, requires manual JAR copying, breaks the integrated build.

## Best Practices

1. **Always add `mavenLocal()` repository** when using snapshot dependencies:
   ```kotlin
   repositories {
       mavenLocal()
       mavenCentral()
   }
   ```

2. **Use project references** instead of Maven coordinates for internal dependencies:
   ```kotlin
   implementation(project(":yappy-models:protobuf-models"))
   // Instead of: implementation("com.krickert.yappy:protobuf-models:1.0.0-SNAPSHOT")
   ```

3. **Configure Docker daemon** for larger builds:
   - Increase daemon memory limits
   - Use local Docker socket instead of TCP
   - Consider using Docker BuildKit for better performance

## Troubleshooting

### Connection Reset Errors

If you still get connection reset errors:
1. Check Docker daemon is running: `docker ps`
2. Verify Docker socket permissions: `ls -la /var/run/docker.sock`
3. Try setting `DOCKER_HOST` environment variable
4. Increase Docker daemon timeout settings

### Image Naming

The plugin creates images based on the project name:
- `chunker` → `chunker:1.0.0-SNAPSHOT`
- `tika-parser` → `tika-parser:1.0.0-SNAPSHOT`
- `yappy-orchestrator` → `yappy-orchestrator:1.0.0-SNAPSHOT`

Note: Some modules may use different naming (e.g., `chunkerapplication` vs `chunker`) depending on the project configuration.

## Future Improvements

1. **Consider migrating to Jib**: Google's Jib plugin doesn't require Docker daemon and handles large projects better
2. **Use BuildKit**: Docker BuildKit provides better caching and performance for multi-stage builds
3. **Optimize layers**: Reduce the size of `project_libs` by excluding unnecessary dependencies

## References

- [Micronaut Gradle Plugin Documentation](https://micronaut-projects.github.io/micronaut-gradle-plugin/latest/)
- [Docker Gradle Plugin Documentation](https://bmuschko.github.io/gradle-docker-plugin/)
- [Gradle Configuration Cache](https://docs.gradle.org/current/userguide/configuration_cache.html)
- [Docker Build Context Best Practices](https://docs.docker.com/build/building/context/)