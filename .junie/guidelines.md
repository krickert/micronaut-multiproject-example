# Project Guidelines for Micronaut Multiproject Example

## Project Overview
This project is a multi-project Micronaut application that serves as a search engine indexer powered by Kafka. It demonstrates how to build a custom Micronaut Bill of Materials (BOM) with Gradle Kotlin DSL. The system is designed for processing data pipelines and consists of several components:

1. **Shared Libraries**:
   - `pipeline-service-core`: Contains the main pipeline logic
   - `protobuf-models`: Defines data models using Protocol Buffers
   - `util`: Common helper functions

2. **Testing Utilities**:
   - `pipeline-service-test-utils`: Assists in testing pipeline components
   - `micronaut-test-consul-container`: Container for Consul testing
   - `pipeline-test-platform`: Platform for pipeline testing

3. **Microservices**:
   - `pipeline-services`: Contains various pipeline service implementations
   - `pipeline-examples`: Example implementations of pipeline services

## Project Structure
- **Root Directory**: Contains build configuration, documentation, and subproject definitions
- **bom**: Bill of Materials for centralized dependency management
- **protobuf-models**: Protocol Buffer model definitions
- **pipeline-service-core**: Core pipeline service implementation
- **pipeline-service-test-utils**: Testing utilities for pipeline services
- **pipeline-services**: Specific pipeline service implementations (e.g., chunker)
- **pipeline-examples**: Example pipeline service implementations
- **pipeline-instance-A**: A specific pipeline instance implementation
- **util**: Utility classes and functions
- **docker-dev**: Docker-based development environment

## Development Environment
A Docker-based development environment is available in the `docker-dev` directory, which includes:
- **Kafka** (in Kraft mode): Message broker
- **Apicurio Registry**: Schema registry
- **Solr** (in cloud mode): Search platform
- **Kafka UI**: Web UI for Kafka management
- **Mono server/Glue Mock**: Mock server for Kafka Connect
- **Consul**: Service discovery
To start the development environment:
```bash
cd docker-dev
docker-compose up -d
```

## Testing Guidelines
1. **Running Tests**:
   - Tests should be run using the standard Gradle test task: `./gradlew test`
   - For specific modules: `./gradlew :module-name:test`
   - For specific tests: `./gradlew :module-name:test --tests "com.krickert.search.TestClass"`

2. **Test Structure**:
   - Tests extend `AbstractPipelineTest` for pipeline service tests
   - Use `@MicronautTest` annotation for Micronaut integration tests
   - Implement `TestPropertyProvider` for test-specific properties

3. **Test Verification**:
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

## Code Style Guidelines
1. **Java Version**: The project uses Java 21
2. **Testing Framework**: JUnit 5 with Micronaut's testing support
3. **Logging**: Use SLF4J with Logback
4. **Documentation**: Document public APIs with Javadoc
5. **Naming Conventions**:
   - Classes: PascalCase
   - Methods and variables: camelCase
   - Constants: UPPER_SNAKE_CASE

## Working with Junie
When working with Junie on this project:
1. **Run Tests**: Always run tests to verify changes
2. **Check Build**: Ensure the project builds successfully
3. **Follow Code Style**: Adhere to the project's code style guidelines
4. **Document Changes**: Provide clear documentation for changes
5. **Consider Dependencies**: Be aware of dependencies between modules

## New service guidelines
1. Implement the `PipelineService` interface
2. Create a test for that service
3. Add the service to the `pipeline-services` module
4. Create a unit test that will automatically test the forwarding and processing of messages by extending "AbstractPipelineTest"

## Additional Resources
- [Micronaut Documentation](https://docs.micronaut.io/)
- [Gradle Documentation](https://docs.gradle.org/)
- [Protocol Buffers Documentation](https://developers.google.com/protocol-buffers)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
