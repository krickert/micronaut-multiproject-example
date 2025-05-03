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
   - No mocktio in tests to simlate kafka, consul, or a schema registry.  This is important because the point of testing this is to 
     ensure the same functionality works in a real environment. 
   - No not change the the test use of docker containers to fix a test.  
   - No not change core functionality that is likely used everywhere (i.e. use YAML files for configuration together with property files)
   - Use property files for test-specific configuration
   - Use `TestPropertySource` for test-specific configuration, but container properties must first be loaded statically before anything 
     is loaded because Micronaut starts up components too early in the lifecycle.
   - Do not use JUnit static beforeall, instead manage this through a static initializer.  Afterall is ok.  This specifically goes to 
     container managed properties.


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



GOALS RIGHT NOW

(do not give a solution yet - we will discuss this as a goal to get here)

listener and producer logic for the app

The idea is that this is going to be a pipeline where we listen and produce the communication between other pipeline implementations that share the same interface.

We will discuss how messages are supposed to be sent and how they are listened to and what concepts we need to deal with

The purpose of this approach is that we are going to make a search pipeline that is flexible for AB testing: each pipeline in itself is it's own "path" of services with it's own configuration.

So - all the user needs to do is define a single function in PipelineProcessor so PipelineServiceImpl can pick it up.  It doesn't care about the internals of that processor - just going to take what it needs to process and give it to that pipeline service.  The processor does the "work" on a PipeDoc (with the help of metadata from the Request and PipeStream).

To better talk about what config properties are, we will demonstrate this into three service types:

* Crawler
* Connector
* Processor (Parser Pipeline Step and Processing Pipeline step)
* Sink

Right now, all of these is a PipelineServiceImpl

* Crawler -
  This helps "seed" the application and passes individual dta to the "connector".  Technically the connector can be the crawler too if it's not a lot of work but often that isn't the case.  There are two good examples here:
   - wikipedia crawler - this downloads a dump file and puts it somewhere for more processing.  It will take each "file" it needs to download and hand it to the connector.   In this case, a wikpedia file parser
   - S3 crawler - this would get the s3 content LIST out of the s3 bucket and hand it to the connector.  In this case, a tika parser (or file parser/default parser) for file processing.
   - JDBC crawler - does a SQL query and hands down the data or IDs for additional processing (this is a design decision)

The crawler works on the PipeStream interface.

For the s3 crawler, System configuration would be s3 connectivity data, but that would not be part of the "shared" consul config.
For the wikipedia crawler, system config would be the wiki website and logic to know what files need parsing and which have been processed already.
for a JDBC crawler, we would know when the last crawl was made and when to push it forward.



* Connector -
  This is what initially "seeds" the pipeline.  It will connect to a JDBC, go to an S3 bucket, etc.  It does NOT parse binary data, it just "gets" the data and sends it to the parser, it will also get any metadata needed.  If backed by a crawler, it typically gets a single ID. So for a wikipedia crawl, the wikipedia connector take the input stream of the wikipedia dump file and process it per-article for downstream parsing.  For the s3 crawl, it will download the s3 file as an input stream and pass it to the tika parser for downstream parsing

* Parser processor
  This is what initially "gets" data from the processor.  The wikipedia article would get mapped to the PipeDoc here and the tika parser would parse the data from a blob into a pipedoc.  All data from a parser is typically already "there" for processing and may only need to retrieve data from a local network.

this also runs as a pipestream

system config here might be how to parse a particular doc and what default mappings we would have.

Pipeconfig would be custom pipeline specific mapping (which is available to every PipeStream), pipe specific properties like chunk size for a chunker or LLM choice for an embedder.

* Pipeline processor
  This doesn't do anymore parsing of data.  The text is all there for processing and now we have processing specific text.

It will parse the text and do things like NER, Chunking, embedding...

* Sink processor
  Goes to the desired system (solr, database, s3, etc...)

So each service has a few concepts going on:

1 - Service level system configuration - this is the system config that isn't part of the overall pipeline configuration.  Stuff like kafka connections, consul connections, schema registry connectivity and serde on the PipeStream are all configured here.
2 - Service level configuration - this is configuration that is "passed" from one pipeline to the next and is pipeline specific.  It will be saved in consul.  
3 - full pipeline configuration - right now this is really just the pipeline name

=======

How we setup each pipeline

Pipeline -> PipeStream -> Pipeline Step

The above is a 1:M map of what we're building.  Each "pipeline" is it's own "path" of configuration it will take to create a document.  The pipeline, in itself, can also be considered a future enhancement to be a "Pipeline Step" - but that functionality won't start until we get this all done.

So for each pipeline config, there is a pipestream.  For each pipestream, there aare multipel pipe steps.

Each "service" implementation is a pipeline step.

The overall pipeline is mainly configuration driven - which is stored in consul

The pipestream itself is similar but unique to it's own run of the pipeline

The pipe step is totally unaware of the above and just processes it's pipeline.


So kafka has the notion of a "consumer group" - we tie this to the pipeline.  The topics in that pipeline right now don't have a naming convention (I don't think that's a good idea to do - but the result is validating any saves to a pipeline to ensure that it doesn't have cross-pipeline contamination or circular references)

grpc is just standalone here, but takes in the same data as a kafka topic so by design it is already contamination-free

Each pipestream is required to identify itself with a pipestream ID.

At this point, the pipeline steps just forward onto one another - either by listening to a topic via kafka or by forwarding to a new pipeline.

We've optimized the format of this - here is an example:

```properties
# Corrected line to point to 'kafka-listen-topics'
pipeline.service.name=test-service
pipeline.name=pipeline1
pipeline.listen.topics=test-input-documents,test-awesome-documents,test-pipeline-input
# This is a test configuration file for pipeline1
pipeline.configs.pipeline1.service.test-service.kafka-listen-topics=test-input-documents,test-awesome-documents,test-pipeline-input
pipeline.configs.pipeline1.service.test-service.kafka-publish-topics=test-output-documents,test-pipeline-output
# Corrected line to point to 'kafka-listen-topics'

# This is a test configuration file for pipeline1
pipeline.configs.pipeline2.service.test-service.kafka-listen-topics=test-input-documents2
pipeline.configs.pipeline2.service.test-service.kafka-publish-topics=test-output-documents2

# Test configuration for Consul
micronaut.application.name=pipeline-service-core-test

# Disable config client to avoid issues with ConsulConfigurationClient
micronaut.config-client.enabled=false

# Consul client configuration
# The actual host and port will be set by ConsulContainer
# consul.client.enabled is set dynamically in tests
consul.client.config.enabled=true
consul.client.config.format=properties
consul.client.config.path=config/pipeline

# Disable Consul watch to avoid issues with Watcher
consul.client.watch.enabled=true

# Disable service registration to prevent connection attempts to default port
consul.client.registration.enabled=true

# Kafka configuration for tests
# The actual bootstrap servers and schema registry URL will be set by AbstractKafkaTest
kafka.enabled=true

# Schema registry configuration
schema.registry.type=apicurio

# Logging configuration
logging.level.com.krickert=DEBUG
```
All tests need to have consul, kafka, and a schema registry running.

Do not swap out the implementation of kafka, always use apache/kafka:latest - NEVER use confluent

All tests need to have consul, kafka, and a schema registry running.

Default to apicurio if apicurio or moto are not selected.  Moto and glue are the same for this context.

The schema registry is backed by google protocol buffers.   The config is done through KV storage in consul.  Micronaut is heavily used where possible.

Since Micronaut doesn't have the ability to make dynamic consumers, we did that ourselves.  But we are using the micronaut standard of how we allocate producers, since forwarding to a topic can be done dynamically.

In this example we show the config for 2 pipelines.

Always use UUID for serialization keys

Unless there's a good reason, it's safe to assume PipeStream is the class to serialize values in most kafka cases.

** THE PROBLEM WITH THE ABOVE **
The implementation might be just fine, but the tests are not there yet - it's a work in progress.  We need to first fix com.krickert.search.pipeline.service.TestPipelineServiceProcessorTest then we will work on the rest.

The goal of the testing framework is that if you make ANY pipeline step, it will make it easy to create a test for it with a single class.

The testing framework should allocate the pipeprocessor as designed as well as the pipeline itself and perform the following tests:

1) Kafka in, kafka out
2) Kafka in, grpc out
3) grpc in, kafka out
4) grpc in, grpc out

And maybe 1-2 1:M tests as well.

These just test that data goes in, and data comes out.  
That the last updated date of the pipe doc is intact and updated to a recent future date
That the step is updated with each step and that the log is added
That any mappings added were successful (this might be a bit more complex)



