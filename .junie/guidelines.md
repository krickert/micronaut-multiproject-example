# Project Guidelines for YAPPY (Yet Another Pipeline Processor: YAPPY)

## Project Overview

YAPPY is a multi-project Micronaut application that serves as a configurable pipeline platform for creating multiple indexes through a
scalable container-based microservice architecture. The system is designed for processing data pipelines with a decentralized approach where
information is shared between components in a control plane and configuration changes happen in near real-time.

The goal is to provide a low-cost, free, enterprise-grade secure document index with the following mission goals:

1. Easy to install - deployable to either a cloud environment or a localized laptop for development
2. Free - built with 100% open source projects
3. Streaming orchestration - documents are indexed through a network of pipelines either synchronously or asynchronously
4. gRPC services + Kafka - all services are built on a common gRPC platform
5. Admin interface - administration of the pipelines are done through a graph
6. Rapid pipeline development in any language - a user only needs to implement a single function and can deploy a service in minutes
7. Standardized monitoring
8. Secure - utilizes multiple configurable security standards

## Architecture Overview

The system is designed with a modular architecture following these core principles:

- **Decoupled Configuration**: Pipeline structure, module definitions, and schema definitions are managed as distinct but related data
  models
- **Centralized Schema Management**: Custom configuration schemas for pipeline modules are stored and versioned in a dedicated "Schema
  Registry"
- **Modularity**: The system is divided into logical modules
- **Framework Agnostic Models**: The core data models are plain Java objects using Jackson for JSON serialization and Lombok for boilerplate
  reduction
- **Live Configuration**: The system allows for live updates to configurations

### Key Components

1. **Configuration Management Subsystem**:
    - **DynamicConfigurationManager**: Orchestrates loading, validation, caching, and live updates from Consul
    - **ConsulConfigFetcher**: Connects to Consul, fetches KVs, deserializes JSON, and implements live watches
    - **ConfigurationValidator**: Validates configurations including referential integrity, schema validation, and loop detection
    - **InMemoryCachedConfigHolder**: Provides thread-safe, atomic caching

2. **Pipeline Execution System**:
    - **PipeStreamEngine**: Central orchestrator service that manages pipeline execution
    - **PipeStepProcessor**: Interface implemented by individual processing steps
    - **Pipeline Models**: Core data structures like PipeDoc, Blob, PipeStream, and HistoryEntry

3. **gRPC Communication**:
    - Services communicate using gRPC for efficient cross-service communication
    - Protocol Buffers are used for strongly-typed data contracts

## Project Structure

- **Root Directory**: Contains build configuration, documentation, and subproject definitions
- **bom**: Bill of Materials for centralized dependency management
- **docker-dev**: Docker development environment setup
- **docs**: Project documentation
- **util**: Utility code and shared functionality
- **yappy-admin**: Administration interface for managing YAPPY
- **yappy-consul-config**: Dynamic Configuration Management Service for loading, watching, validating, and providing live updates of
  pipeline and schema configurations stored in Consul
- **yappy-engine**: Core engine that orchestrates pipeline execution and module management
- **yappy-models**: Contains multiple submodules:
    - **pipeline-config-models**: Defines the structure of pipelines, steps, schema registration, and their configurations
    - **protobuf-models**: Protocol Buffer model definitions for gRPC communication
    - **pipeline-config-models-test-utils**: Test utilities for pipeline configuration models
    - **protobuf-models-test-data-resources**: Test resources for protobuf models
- **yappy-modules**: Collection of pipeline processing modules:
    - **chunker**: Text chunking module
    - **echo**: Simple echo module for testing
    - **embedder**: Text embedding generation module
    - **opensearch-sink**: Module for indexing to OpenSearch
    - **project-generator**: Scaffolding tool for new modules
    - **s3-connector**: AWS S3 connector module
    - **tika-parser**: Document parsing module
    - **web-crawler-connector**: Web crawling module
    - **wikipedia-connector**: Wikipedia content connector
- **yappy-test-resources**: TestContainers configuration and test resources
- **yappy-ui**: User interface for interacting with YAPPY

## Development Environment

A Docker-based development environment is available in the `docker-dev` directory, which includes:

- **Kafka** (in Kraft mode): Message broker (localhost:9092)
- **Apicurio Registry**: Schema registry (http://localhost:8080)
- **Solr** (in cloud mode): Search platform (http://localhost:8983)
- **Kafka UI**: Web UI for Kafka management (http://localhost:8081)
- **Moto server/Glue Mock**: Mock server for AWS services including Glue Schema Registry (localhost:5001)
- **OpenSearch**: Search and analytics engine (http://localhost:9200)
- **OpenSearch Dashboards**: UI for OpenSearch (http://localhost:5601)
- **Consul**: Service discovery and configuration storage (http://localhost:8500)

To start the development environment:

```bash
cd docker-dev
docker-compose up -d
```

To stop all services:

```bash
cd docker-dev
docker-compose down
```

To verify the setup:

```bash
cd docker-dev
./test-docker-setup.sh
```

## Testing Guidelines

1. **Running Tests**:
    - Tests should be run using the standard Gradle test task: `./gradlew test`
    - For specific modules: `./gradlew :module-name:test`
    - For specific tests: `./gradlew :module-name:test --tests "com.krickert.search.TestClass"`

2. **Test Structure**:
    - Unit tests should be placed in the same package as the class being tested
    - Integration tests should be placed in a separate package with "integration" in the name
    - Use Micronaut's testing support for integration tests
    - Use TestContainers for tests that require external services

3. **TestContainers Configuration**:
    - see the README for the 

4. **Test Verification**:
    - Always run tests to verify changes
    - Ensure all tests pass before submitting changes
    - Add new tests for new functionality

## Build Guidelines

1. **Building the Project**:
    - Use Gradle with the Kotlin DSL: `./gradlew build`
    - For specific modules: `./gradlew :module-name:build`

2. **Dependency Management**:
    - Use the BOM for centralized dependency management
    - Add new dependencies to the appropriate module's build.gradle.kts file
    - Use the libs.versions.toml file for version management
    - Always favor the Micronaut BOM for Micronaut dependencies

## Code Style Guidelines

1. **Java Version**: The project uses Java 21
2. **Testing Framework**: JUnit 5 with Micronaut's testing support
3. **Logging**: Use SLF4J with Logback
4. **Documentation**: Document public APIs with Javadoc
5. **Naming Conventions**:
    - Classes: PascalCase
    - Methods and variables: camelCase
    - Constants: UPPER_SNAKE_CASE
6. **Error Handling**:
    - Use appropriate exception types
    - Log exceptions with context information
    - Provide meaningful error messages

## Pipeline Development Guidelines

1. **Pipeline Configuration**:
    - Pipelines are defined in the `PipelineClusterConfig` model
    - Each pipeline consists of multiple steps defined in `PipelineStepConfig`
    - Steps can be connected via explicit `nextSteps`/`errorSteps` or through Kafka topics

2. **Implementing Pipeline Steps**:
    - Implement the `PipeStepProcessor` gRPC service interface
    - Focus on implementing the `ProcessDocument` method
    - Handle the input document, perform processing, and return the updated document
    - Use the provided configuration parameters for customization

3. **New Service Guidelines**:
    - Implement the `PipelineService` interface
    - Create a test for that service
    - Add the service to the appropriate module
    - Create a unit test that will automatically test the forwarding and processing of messages by extending "AbstractPipelineTest"

## Working with Junie

When working with Junie on this project:

1. **Run Tests**: Always run tests to verify changes
2. **Check Build**: Ensure the project builds successfully
3. **Follow Code Style**: Adhere to the project's code style guidelines
4. **Document Changes**: Provide clear documentation for changes
5. **Consider Dependencies**: Be aware of dependencies between modules

## Additional Resources

- [Micronaut Documentation](https://docs.micronaut.io/)
- [Gradle Documentation](https://docs.gradle.org/)
- [Protocol Buffers Documentation](https://developers.google.com/protocol-buffers)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [gRPC Documentation](https://grpc.io/docs/)
- [Consul Documentation](https://developer.hashicorp.com/consul/docs)
- [Solr Documentation](https://solr.apache.org/guide/)
- [OpenSearch Documentation](https://opensearch.org/docs/latest/)
- [OpenSearch Dashboards Documentation](https://opensearch.org/docs/latest/dashboards/index/)
