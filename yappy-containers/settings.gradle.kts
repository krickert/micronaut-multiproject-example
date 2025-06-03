// Configure plugin management
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

// Configure dependency resolution
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.spring.io/milestone") }
        maven { url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/") }
    }
    versionCatalogs {
        create("mn") {
            from("io.micronaut.platform:micronaut-platform:4.8.2")
        }
    }
}

rootProject.name = "yappy-containers"

include(
    "engine-base",
    "engine-tika-parser",
    "engine-chunker",
    "engine-embedder",
    "test-connector",
    "engine-opensearch-sink"
)

// Include parent projects for dependency resolution
includeBuild("../") {
    dependencySubstitution {
        substitute(module("com.krickert.search:yappy-engine")).using(project(":yappy-engine"))
        substitute(module("com.krickert.search:yappy-models")).using(project(":yappy-models"))
        substitute(module("com.krickert.search.modules:tika-parser")).using(project(":yappy-modules:tika-parser"))
        substitute(module("com.krickert.search.modules:chunker")).using(project(":yappy-modules:chunker"))
        substitute(module("com.krickert.search.modules:embedder")).using(project(":yappy-modules:embedder"))
        substitute(module("com.krickert.search.modules:test-connector")).using(project(":yappy-modules:test-connector"))
        substitute(module("com.krickert.search.modules:opensearch-sink")).using(project(":yappy-modules:opensearch-sink"))
    }
}