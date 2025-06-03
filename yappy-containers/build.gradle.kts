plugins {
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("io.micronaut.application") version "4.5.3" apply false
    id("io.micronaut.aot") version "4.5.3" apply false
    id("io.micronaut.crac") version "4.5.3" apply false
    id("io.micronaut.test-resources") version "4.5.3" apply false
}

allprojects {
    group = "com.krickert.yappy.containers"
    version = System.getenv("VERSION") ?: "1.0.0-SNAPSHOT"
}

subprojects {
    // Apply common properties
    ext {
        set("dockerRegistry", System.getenv("DOCKER_REGISTRY") ?: findProperty("docker.registry")?.toString() ?: "localhost:5000")
        set("dockerNamespace", System.getenv("DOCKER_NAMESPACE") ?: findProperty("docker.namespace")?.toString() ?: "yappy")
    }
    
    // Common Java settings
    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }
    
    
    // CRaC support configuration (for future)
    tasks.withType<JavaExec> {
        if (System.getProperty("crac.enabled") == "true") {
            jvmArgs("-XX:CRaCCheckpointTo=cr")
            jvmArgs("-XX:+UnlockDiagnosticVMOptions")
            jvmArgs("-XX:+CRAllowToSkipCheckpoint")
        }
    }
}

// Root level tasks
tasks.register("buildAllContainers") {
    group = "docker"
    description = "Build all container images"
    subprojects.forEach { subproject ->
        subproject.tasks.findByName("dockerBuild")?.let {
            dependsOn("${subproject.path}:dockerBuild")
        }
    }
}

tasks.register("pushAllContainers") {
    group = "docker"
    description = "Push all container images to registry"
    subprojects.forEach { subproject ->
        subproject.tasks.findByName("dockerPush")?.let {
            dependsOn("${subproject.path}:dockerPush")
        }
    }
}

tasks.register("buildAllNativeContainers") {
    group = "docker"
    description = "Build all native container images"
    subprojects.forEach { subproject ->
        subproject.tasks.findByName("dockerBuildNative")?.let {
            dependsOn("${subproject.path}:dockerBuildNative")
        }
    }
}

tasks.register("setupLocalRegistry") {
    group = "docker"
    description = "Set up local Docker registry"
    doLast {
        exec {
            commandLine("docker", "run", "-d", "-p", "5000:5000", "--restart=always", "--name", "registry", "registry:2")
            isIgnoreExitValue = true
        }
        println("Local registry available at localhost:5000")
    }
}