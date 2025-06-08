pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("mn") {
            from("io.micronaut.platform:micronaut-platform:4.8.2")
        }
    }
}

rootProject.name="yappy-consul-config"

// Include project dependencies
includeBuild("..") {
    dependencySubstitution {
        substitute(module("com.krickert.search:yappy-test-resources")).using(project(":yappy-test-resources"))
        substitute(module("com.krickert.search:consul-test-resource")).using(project(":yappy-test-resources:consul-test-resource"))
    }
}