plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "@@GROUP_ID@@"

repositories {
    mavenCentral()
}

dependencies {
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.discovery.core)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.javax.annotation.api)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation(project(":yappy-models:protobuf-models"))
}


application {
    mainClass = "@@BASE_PACKAGE@@.@@MODULE_NAME_PASCAL_CASE@@Application"
}
java {
    sourceCompatibility = JavaVersion.toVersion("21")
    targetCompatibility = JavaVersion.toVersion("21")
}


sourceSets {
    main {
        java {
            srcDirs("build/generated/source/proto/main/grpc")
            srcDirs("build/generated/source/proto/main/java")
        }
    }
}

micronaut {
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("@@BASE_PACKAGE@@.*")
    }
}
