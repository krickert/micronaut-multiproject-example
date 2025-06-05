plugins {
    id("com.palantir.docker") version "0.36.0"
}

docker {
    name = "yappy/engine-tika-parser:${project.version}"
    
    // Build context is the root project directory
    setDockerfile(file("Dockerfile"))
    buildContext(rootProject.projectDir)
    
    // Copy resources to build directory
    files(
        "src/main/resources/supervisord.conf",
        "src/main/resources/application.yml",
        "src/main/resources/module-application.yml",
        "src/main/resources/start.sh"
    )
    
    // Build args if needed
    buildArgs(mapOf(
        "ENGINE_JAR" to "yappy-engine/build/libs/yappy-engine-${project.version}-all.jar",
        "MODULE_JAR" to "yappy-modules/tika-parser/build/libs/tika-parser-${project.version}-all.jar"
    ))
    
    // Labels
    labels(mapOf(
        "maintainer" to "YAPPY Team",
        "description" to "YAPPY Engine with Tika Parser module",
        "version" to project.version.toString()
    ))
}

// Task to ensure JARs are built before Docker image
tasks.docker {
    dependsOn(":yappy-engine:shadowJar")
    dependsOn(":yappy-modules:tika-parser:shadowJar")
}