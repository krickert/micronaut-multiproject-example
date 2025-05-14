plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.echo"

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
    mainClass = "com.krickert.yappy.modules.echo.EchoApplication"
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
        annotations("com.krickert.yappy.modules.echo.*")
    }
}



