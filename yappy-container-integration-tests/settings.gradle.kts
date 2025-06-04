rootProject.name = "yappy-container-integration-tests"

// Include parent build for dependencies
includeBuild("../") {
    dependencySubstitution {
        substitute(module("com.krickert.search:yappy-models")).using(project(":yappy-models"))
        substitute(module("com.krickert.search:protobuf-models")).using(project(":yappy-models:protobuf-models"))
        substitute(module("com.krickert.search:pipeline-config-models")).using(project(":yappy-models:pipeline-config-models"))
    }
}