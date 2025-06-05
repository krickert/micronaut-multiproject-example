plugins {
    id("io.micronaut.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = rootProject.version
group = "com.krickert.yappy.containers"

repositories {
    mavenCentral()
}

dependencies {
    // Engine dependencies
    implementation(project(":yappy-engine"))
    
    // Module dependencies
    implementation(project(":yappy-modules:tika-parser"))
}

application {
    mainClass.set("com.krickert.yappy.bootstrap.EngineApplication")
}

micronaut {
    runtime("netty")
    processing {
        incremental(true)
    }
}

// Ensure we build all dependencies
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    dependsOn(":yappy-modules:tika-parser:shadowJar")
    isZip64 = true
}

// Copy files for Docker build
tasks.register<Copy>("prepareDockerContext") {
    dependsOn("shadowJar", ":yappy-modules:tika-parser:shadowJar")
    
    // Copy tika-parser JAR
    from(project(":yappy-modules:tika-parser").tasks.named("shadowJar", Jar::class.java))
    
    // Copy config files
    from("src/main/resources") {
        include("supervisord.conf", "application.yml", "module-application.yml", "start.sh")
        into("config")
    }
    
    into("build/docker/main/layers")
    
    // Rename tika jar
    rename { fileName ->
        if (fileName.contains("tika-parser") && fileName.endsWith(".jar")) {
            "tika-parser.jar"
        } else {
            fileName
        }
    }
}

tasks.named("dockerBuild") {
    dependsOn("prepareDockerContext")
    doFirst {
        // Copy our custom Dockerfile over the generated one
        copy {
            from("src/main/docker/Dockerfile")
            into(file("build/docker/main"))
        }
    }
}

