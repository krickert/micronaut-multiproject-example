import io.micronaut.testresources.buildtools.KnownModules
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

plugins {
    id("io.micronaut.minimal.application") version "4.5.3"
    id("io.micronaut.test-resources") version "4.5.3"
    id("com.gradleup.shadow") version "8.3.6"
}

version = "1.0.0-SNAPSHOT"
group = "com.krickert.yappy.modules.opensearchsink"

repositories {
    mavenCentral()
}

dependencies {
    testAnnotationProcessor(mn.micronaut.inject.java)
    annotationProcessor(mn.micronaut.serde.processor)
    implementation(mn.micronaut.grpc.runtime)
    implementation(mn.micronaut.serde.jackson)
    implementation(mn.javax.annotation.api)
    runtimeOnly(mn.logback.classic)
    runtimeOnly(mn.snakeyaml)
    implementation("io.micronaut.reactor:micronaut-reactor")
    implementation("io.micronaut.reactor:micronaut-reactor-http-client")

    implementation(project(":yappy-models:protobuf-models"))
    implementation(mn.grpc.services)
    implementation(mn.grpc.stub)
    implementation(mn.micronaut.http.client.core)
    implementation("io.micronaut.grpc:micronaut-protobuff-support")

    // OpenSearch dependencies
    implementation("org.opensearch.client:opensearch-java:3.0.0")

    // Protobuf JSON conversion
    implementation("com.google.protobuf:protobuf-java-util:3.25.3")

    // Kafka dependencies
    implementation("io.apicurio:apicurio-registry-protobuf-serde-kafka:3.0.7")
    testImplementation(mn.assertj.core)
    // https://mvnrepository.com/artifact/org.opensearch.client/opensearch-rest-high-level-client
    implementation("org.opensearch.client:opensearch-rest-high-level-client:3.0.0")
    // https://mvnrepository.com/artifact/org.opensearch.client/opensearch-rest-client
    implementation("org.opensearch.client:opensearch-rest-client:3.0.0")
    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.0.86")

    // Lombok for builder pattern
    compileOnly("org.projectlombok:lombok:1.18.32")
    annotationProcessor("org.projectlombok:lombok:1.18.32")

    // Testing dependencies
    testImplementation(mn.junit.jupiter.params)
    testImplementation("org.junit.jupiter:junit-jupiter-api")
    testImplementation("io.micronaut.test:micronaut-test-junit5")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.junit.jupiter:junit-jupiter-engine")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.11.0")

    // Test resources
    testImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apache-kafka-test-resource"))
    testImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:apicurio-test-resource"))
    testImplementation(project(":yappy-test-resources:opensearch3-test-resource"))
    testResourcesImplementation(project(":yappy-test-resources:opensearch3-test-resource"))
    implementation("org.opensearch:protobufs:0.3.0")
    // https://mvnrepository.com/artifact/org.fusesource.jansi/jansi
    implementation("org.fusesource.jansi:jansi:2.4.2")
    // OpenSearch TestContainer
    //testImplementation("org.opensearch:opensearch-testcontainers:3.0.0")
    implementation("org.apache.httpcomponents.client5:httpclient5:5.2.1")
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
}

application {
    mainClass = "com.krickert.yappy.modules.opensearchsink.Application"
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
        annotations("com.krickert.yappy.modules.opensearchsink.*")
    }
    testResources {
        enabled.set(true)
        inferClasspath.set(true)
        clientTimeout.set(60)
        sharedServer.set(true)
        debugServer.set(false)
    }
}

/**
 * Task to download OpenSearch protobufs from GitHub.
 * 
 * This task downloads the proto files from the opensearch-project/opensearch-protobufs GitHub repository
 * at the version specified in the project's dependencies. The proto files are extracted to the
 * src/main/proto-opensearch directory for reference.
 * 
 * The version of the protobufs is determined from the "org.opensearch:protobufs" dependency.
 * If the dependency version changes, running this task will download the updated proto files.
 */
abstract class DownloadOpenSearchProtobufsTask : DefaultTask() {
    @get:Input
    abstract val protobufsVersion: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:OutputFile
    abstract val versionFile: RegularFileProperty

    @TaskAction
    fun downloadProtobufs() {
        val version = protobufsVersion.get()
        val protoDir = outputDir.get().asFile
        val versionFilePath = versionFile.get().asFile

        // Check if version file exists and contains the current version
        if (versionFilePath.exists()) {
            val existingVersion = versionFilePath.readText().trim()
            if (existingVersion == version) {
                logger.lifecycle("OpenSearch protobufs version ${version} already downloaded. Skipping download.")
                return
            }
        }

        // Clean the directory first, but preserve the version file if it exists
        protoDir.listFiles()?.forEach { 
            if (it.name != "tag_version.txt") {
                it.delete()
            }
        }

        // GitHub repository URL
        val repoUrl = "https://github.com/opensearch-project/opensearch-protobufs/archive/refs/tags/${version}.zip"

        logger.lifecycle("Downloading OpenSearch protobufs version ${version} from ${repoUrl}")

        // Download and extract the ZIP file
        val tempFile = File.createTempFile("opensearch-protobufs", ".zip")
        tempFile.deleteOnExit()

        URL(repoUrl).openStream().use { input ->
            Files.copy(input, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        // Extract proto files from the ZIP
        var protoFilesCount = 0

        ZipInputStream(tempFile.inputStream()).use { zipIn ->
            var entry = zipIn.nextEntry
            while (entry != null) {
                val entryName = entry.name

                // Only extract .proto files
                if (entryName.endsWith(".proto")) {
                    val fileName = entryName.substringAfterLast("/")
                    val outFile = File(protoDir, fileName)

                    logger.info("Extracting ${fileName}")

                    // Create output stream for the file
                    outFile.outputStream().use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        while (zipIn.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }

                    protoFilesCount++
                }
                zipIn.closeEntry()
                entry = zipIn.nextEntry
            }
        }

        if (protoFilesCount == 0) {
            logger.error("No .proto files found in the ZIP file!")
        } else {
            logger.lifecycle("Extracted ${protoFilesCount} proto files to ${protoDir.absolutePath}")

            // Write the version to the version file
            versionFilePath.writeText(version)
            logger.lifecycle("Wrote version ${version} to ${versionFilePath.absolutePath}")
        }
    }
}

// Get the OpenSearch protobufs version from the project's dependencies
val openSearchProtobufsDependency = configurations.compileClasspath.get().dependencies
    .find { it.group == "org.opensearch" && it.name == "protobufs" }
val openSearchProtobufsVersion = openSearchProtobufsDependency?.version ?: "0.3.0"

// Register the task
tasks.register<DownloadOpenSearchProtobufsTask>("downloadOpenSearchProtobufs") {
    group = "opensearch"
    description = "Downloads OpenSearch protobufs from GitHub and places them in src/main/proto-opensearch"

    protobufsVersion.set(openSearchProtobufsVersion)
    outputDir.set(file("src/main/proto-opensearch"))
    versionFile.set(file("src/main/proto-opensearch/tag_version.txt"))
}
