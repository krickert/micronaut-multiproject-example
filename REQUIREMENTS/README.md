# YAPPY Engine Requirements Documentation

This directory contains the comprehensive requirements and design documentation for the YAPPY Engine distributed data processing system. The documentation has been organized into focused sections for easier navigation and understanding.

## 🆕 New Architecture Documents

**Important**: The YAPPY Engine architecture has been significantly simplified. The engine is now a pure orchestration layer that coordinates simple gRPC services (modules) through configuration-driven pipelines.

### Key New Documents:

13. **[13-module-registration-flow.md](13-module-registration-flow.md)** 🔴 NEW
    - Complete module registration process
    - CLI commands and usage
    - CI/CD integration examples
    - Error handling and troubleshooting

14. **[14-connector-configuration.md](14-connector-configuration.md)** 🔴 NEW
    - Connector mapping configuration
    - Source identifier routing
    - Pipeline designer integration
    - Best practices

15. **[15-grpc-first-implementation.md](15-grpc-first-implementation.md)** 🔴 NEW
    - gRPC-only initial implementation
    - Backpressure and error handling
    - Migration path to Kafka
    - Testing strategy

16. **[16-engine-internals.md](16-engine-internals.md)** 🔴 NEW
    - PipeStream processing model
    - Metadata tracking and monitoring
    - Connection pooling and health routing
    - Large document handling
    - Security roadmap

17. **[17-codebase-navigation-index.md](17-codebase-navigation-index.md)** 🔴 NEW
    - Complete codebase structure guide
    - Component locations and purposes
    - Key interfaces and patterns
    - Common tasks and debugging tips
    - Designed for LLM navigation

## Document Index

### 📋 Foundation

1. **[01-overview-and-principles.md](01-overview-and-principles.md)**
   - Project overview and goals
   - Foundational design principles
   - Core architecture decisions
   - Development philosophy

2. **[02-bootstrap-and-setup.md](02-bootstrap-and-setup.md)**
   - Initial engine bootstrapping ("Easy Peasy" plan)
   - Consul connection setup
   - Cluster initialization and association
   - Transition to normal operations

3. **[03-enhanced-operations.md](03-enhanced-operations.md)**
   - Service status models and tracking
   - Engine-managed module registration
   - Health check integration
   - Service lifecycle management

### 🔧 Technical Architecture

4. **[04-status-models-and-api.md](04-status-models-and-api.md)**
   - ServiceOperationalStatus enum definition
   - ServiceAggregatedStatus model
   - REST and gRPC API specifications
   - Health check standards

5. **[05-current-status-achievements.md](05-current-status-achievements.md)**
   - Completed milestones
   - Architecture improvements
   - Lessons learned
   - Technical debt addressed

6. **[06-module-management.md](06-module-management.md)**
   - Module discovery and invocation
   - Registration and lifecycle
   - Configuration management
   - Security considerations

7. **[07-implementation-details.md](07-implementation-details.md)**
   - Component implementation plans
   - Interface definitions
   - Integration points
   - Performance optimizations

### 🚀 Infrastructure

8. **[08-kafka-integration.md](08-kafka-integration.md)**
   - Topic naming conventions
   - Consumer group management
   - Kafka slot management
   - Cross-pipeline communication

9. **[09-configuration-management.md](09-configuration-management.md)**
   - Consul configuration structure
   - Dynamic configuration manager
   - Validation pipeline
   - Configuration routing

10. **[10-containerization.md](10-containerization.md)**
    - Docker container architecture
    - Build process and optimization
    - Supervisord configuration
    - Kubernetes deployment

### 📊 Quality & Future

11. **[11-testing-strategy.md](11-testing-strategy.md)**
    - Testing principles and categories
    - Integration testing approach
    - Performance testing
    - CI/CD integration

12. **[12-future-roadmap.md](12-future-roadmap.md)**
    - Phased implementation plan
    - Observability and monitoring
    - Admin UI and search features
    - Enterprise capabilities

## Quick Start Guide

### For the New Architecture

If you're working with the new simplified architecture, start here:

1. **[01-overview-and-principles.md](01-overview-and-principles.md)** - Understand the orchestration model
2. **[13-module-registration-flow.md](13-module-registration-flow.md)** - Learn how modules are registered
3. **[15-grpc-first-implementation.md](15-grpc-first-implementation.md)** - Understand the gRPC-only approach
4. **[14-connector-configuration.md](14-connector-configuration.md)** - See how data enters the system

### For Understanding the Full System

1. Start with **[01-overview-and-principles.md](01-overview-and-principles.md)** to understand the project goals
2. Read **[06-module-management.md](06-module-management.md)** to understand module architecture
3. Review **[02-bootstrap-and-setup.md](02-bootstrap-and-setup.md)** to understand how the system starts
4. Explore specific technical areas based on your interests

## Key Concepts

### New Simplified Architecture
- **Pure Orchestration**: Engine contains no business logic, only routing
- **Simple Modules**: gRPC services implement just 3 methods (ProcessData, GetServiceRegistration, Health)
- **Explicit Registration**: CI/CD must register modules using CLI with engine endpoint
- **Configuration-Driven**: All routing determined by pipeline configuration
- **Language Agnostic**: Any language with gRPC support can implement a module

### Core Technologies
- **gRPC**: Synchronous inter-service communication
- **Kafka**: Asynchronous message passing
- **Consul**: Service discovery and configuration
- **Protobuf**: Cross-language data serialization
- **Micronaut**: Modern JVM framework

### Design Principles
- **100% Real Integration Tests**: No mocks in integration tests
- **No Infrastructure in Modules**: Modules know nothing about Consul/Kafka
- **gRPC-First Development**: Initial implementation uses only gRPC
- **Flexible Deployment**: Support for containerized and kubernetes deployments
- **Production-Ready**: Built for real-world scenarios

## Document Maintenance

These documents are derived from the original `current_instructions.md` file and represent the current state of the project as of the last update. They should be kept in sync with the implementation as the project evolves.

### Contributing
When updating documentation:
1. Keep sections focused and concise
2. Use concrete examples where possible
3. Maintain consistency with existing terminology
4. Update the index when adding new sections

## Related Resources

- Main project repository: `/yappy-work`
- Protobuf definitions: `/yappy-models/protobuf-models/src/main/proto/`
- Pipeline config models: `/yappy-models/pipeline-config-models/`
- Module implementations: `/yappy-modules/`

## Questions or Feedback?

For questions about the requirements or to provide feedback:
- Create an issue in the project repository
- Consult the troubleshooting sections in relevant documents
- Review the test cases for implementation examples