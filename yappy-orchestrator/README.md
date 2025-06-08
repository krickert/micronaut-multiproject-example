# Yappy Orchestrator

A modular orchestration engine for the Yappy search pipeline platform. This module provides the core engine functionality split into focused sub-modules for better separation of concerns, easier testing, and more flexible deployment.

## Architecture

The orchestrator is divided into the following sub-modules:

### Core Modules

- **engine-core**: Core interfaces, models, and common utilities used across all modules
- **engine-bootstrap**: Bootstrap and initialization services for engine startup
- **engine-registration**: Module registration, validation, and lifecycle management  
- **engine-health**: Health monitoring, health checks, and status management
- **engine-kafka**: Kafka consumer management, message processing, and slot-based partitioning
- **engine-pipeline**: Pipeline execution, step processing, and message routing
- **engine-grpc**: gRPC service implementations for remote communication
- **engine-config**: Configuration management and integration with Consul

## Benefits of Modular Design

1. **Better Test Isolation**: Each module can be tested independently with focused unit tests
2. **Easier Component Replacement**: Individual modules can be rewritten without affecting others
3. **Clear Separation of Concerns**: Each module has a specific responsibility
4. **Flexible Deployment**: Modules can potentially be deployed separately if needed
5. **Improved Build Times**: Only changed modules need to be rebuilt

## Building

From the root of yappy-orchestrator:
```bash
./gradlew build
```

From the platform root:
```bash
./gradlew :yappy-orchestrator:build
```

## Module Dependencies

The dependency graph flows as follows:
- All modules depend on `engine-core`
- `engine-registration` depends on `engine-health`
- `engine-kafka` depends on `engine-pipeline`
- `engine-grpc` depends on `engine-registration` and `engine-pipeline`
- The main orchestrator app depends on all sub-modules

## Micronaut 4.8.2 Documentation

- [User Guide](https://docs.micronaut.io/4.8.2/guide/index.html)
- [API Reference](https://docs.micronaut.io/4.8.2/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/4.8.2/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)
---

- [Shadow Gradle Plugin](https://gradleup.com/shadow/)
- [Micronaut Gradle Plugin documentation](https://micronaut-projects.github.io/micronaut-gradle-plugin/latest/)
- [GraalVM Gradle Plugin documentation](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
## Feature management documentation

- [Micronaut Management documentation](https://docs.micronaut.io/latest/guide/index.html#management)


## Feature kafka documentation

- [Micronaut Kafka Messaging documentation](https://micronaut-projects.github.io/micronaut-kafka/latest/guide/index.html)


## Feature discovery-consul documentation

- [Micronaut Consul Service Discovery documentation](https://docs.micronaut.io/latest/guide/index.html#serviceDiscoveryConsul)

- [https://www.consul.io](https://www.consul.io)


## Feature testcontainers documentation

- [https://www.testcontainers.org/](https://www.testcontainers.org/)


## Feature control-panel documentation

- [Micronaut Control Panel documentation](https://micronaut-projects.github.io/micronaut-control-panel/latest/guide/index.html)


## Feature discovery-client documentation

- [Micronaut Discovery Client documentation](https://micronaut-projects.github.io/micronaut-discovery-client/latest/guide/)


## Feature mockito documentation

- [https://site.mockito.org](https://site.mockito.org)


## Feature lombok documentation

- [Micronaut Project Lombok documentation](https://docs.micronaut.io/latest/guide/index.html#lombok)

- [https://projectlombok.org/features/all](https://projectlombok.org/features/all)


## Feature openapi-explorer documentation

- [Micronaut OpenAPI Explorer View documentation](https://micronaut-projects.github.io/micronaut-openapi/latest/guide/#openapiExplorer)

- [https://github.com/Authress-Engineering/openapi-explorer](https://github.com/Authress-Engineering/openapi-explorer)


## Feature micronaut-aot documentation

- [Micronaut AOT documentation](https://micronaut-projects.github.io/micronaut-aot/latest/guide/)


## Feature junit-params documentation

- [https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parameterized-tests)


## Feature junit-platform-suite-engine documentation

- [https://junit.org/junit5/docs/current/user-guide/#junit-platform-suite-engine-setup](https://junit.org/junit5/docs/current/user-guide/#junit-platform-suite-engine-setup)


## Feature openapi documentation

- [Micronaut OpenAPI Support documentation](https://micronaut-projects.github.io/micronaut-openapi/latest/guide/index.html)

- [https://www.openapis.org](https://www.openapis.org)


## Feature assertj documentation

- [https://assertj.github.io/doc/](https://assertj.github.io/doc/)


## Feature discovery-core documentation

- [Micronaut Discovery Core documentation](https://micronaut-projects.github.io/micronaut-discovery-client/latest/guide/)


## Feature reactor documentation

- [Micronaut Reactor documentation](https://micronaut-projects.github.io/micronaut-reactor/snapshot/guide/index.html)


## Feature config-consul documentation

- [Micronaut Consul Distributed Configuration documentation](https://docs.micronaut.io/latest/guide/index.html#distributedConfigurationConsul)

- [https://www.consul.io](https://www.consul.io)


## Feature http-client documentation

- [Micronaut HTTP Client documentation](https://docs.micronaut.io/latest/guide/index.html#nettyHttpClient)


## Feature aws-v2-sdk documentation

- [Micronaut AWS SDK 2.x documentation](https://micronaut-projects.github.io/micronaut-aws/latest/guide/)

- [https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/welcome.html](https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/welcome.html)


## Feature test-resources documentation

- [Micronaut Test Resources documentation](https://micronaut-projects.github.io/micronaut-test-resources/latest/guide/)


## Feature crac documentation

- [Micronaut Support for CRaC (Coordinated Restore at Checkpoint) documentation](https://micronaut-projects.github.io/micronaut-crac/latest/guide)

- [https://wiki.openjdk.org/display/CRaC](https://wiki.openjdk.org/display/CRaC)


## Feature awaitility documentation

- [https://github.com/awaitility/awaitility](https://github.com/awaitility/awaitility)


