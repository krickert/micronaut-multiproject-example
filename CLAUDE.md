# YAPPY Engine - Claude AI Context Document

## Project Overview

YAPPY Engine is a distributed data processing pipeline orchestration system designed as a **pure orchestration layer**. It coordinates data flow through configurable pipelines of simple gRPC microservices (modules) without containing any business logic itself.

### Current Status
- **Branch**: `commons-event-driven-architecture` - Implementing event-driven refactoring
- **Phase**: Engine rewrite Phase 2 (Clean Up Codebase) - Almost complete
- **Priority**: Stabilize environment → End-to-end testing → Engine rewrite → Kafka service refactoring

### Key Architecture Principles

1. **Pure Orchestration** - Engine contains NO business logic, only routing
2. **Language Agnostic Modules** - Any gRPC-capable language can implement modules
3. **Configuration-Driven** - All routing from Consul-stored pipeline configs
4. **Explicit Registration** - CI/CD registers modules via CLI (no self-registration)
5. **Infrastructure Abstraction** - Modules know nothing about Consul/Kafka/orchestration

## Tech Stack

- **Language**: Java 21
- **Build**: Gradle 8.x with Kotlin DSL
- **Framework**: Micronaut 4.8.2
- **Communication**: gRPC 1.72.0 (sync), Apache Kafka (async)
- **Serialization**: Protocol Buffers 3.25.7
- **Service Discovery**: HashiCorp Consul
- **Schema Registry**: Apicurio AND AWS Glue (different clients)
- **Testing**: JUnit 5, Testcontainers (100% real integration tests)
- **Observability**: Prometheus/Grafana, JMX
- **Containerization**: Docker (standalone first, then Swarm/K8s)

## Project Structure

```
yappy/
├── yappy-orchestrator/          # Core engine (being modularized)
│   ├── engine-core/            # Interfaces, models, utilities
│   ├── engine-bootstrap/       # Startup services
│   ├── engine-registration/    # Module registration
│   ├── engine-health/          # Health monitoring
│   ├── engine-kafka/           # Kafka integration
│   ├── engine-pipeline/        # Pipeline execution
│   ├── engine-grpc/            # gRPC services
│   └── engine-config/          # Consul configuration
├── yappy-models/               # Data models & contracts
│   ├── protobuf-models/        # Protobuf definitions
│   └── pipeline-config-models/ # Pipeline configs
├── yappy-modules/              # Processing modules
│   ├── tika-parser/           # Document parsing (WORKING)
│   ├── chunker/               # Text chunking (WORKING)
│   ├── embedder/              # Embeddings (WORKING)
│   ├── echo/                  # Test module (WORKING)
│   └── test-module/           # Test module (WORKING)
├── yappy-commons/              # [TO CREATE] Shared event types
├── yappy-consul-config/        # Dynamic configuration
├── yappy-kafka-slot-manager/   # Partition management
├── yappy-module-registration/  # CLI for registration
└── REQUIREMENTS/               # Comprehensive docs
```

## Current Development Focus

### Integration Testing Issue
Found a fundamental flaw in integration testing approach - currently stabilizing this before proceeding.

### Event-Driven Architecture Migration
- Creating `yappy-commons` module for shared event types
- Replacing `PipeStreamEngine` with Micronaut events
- Events for **same-process messaging only** (not between engine steps)
- gRPC remains for inter-service communication (scalable via Consul)

### Kafka Service Refactoring
Almost complete. Key changes:
1. Replace `PipeStreamEngine` with event publishing
2. Convert `CompletableFuture` → Project Reactor (`Mono`/`Flux`)
3. Integrate with slot manager service
4. Implement AWS Glue properties support

## How YAPPY Works

1. **Data Entry**: Connectors submit data to Connector Engine
2. **Pipeline Routing**: Engine looks up pipeline config from Consul by source ID
3. **Module Processing**: Data flows through gRPC modules per pipeline definition
4. **Transport**: Direct gRPC (now) or async Kafka (future)
5. **Output**: Data reaches sinks (OpenSearch, databases, S3, etc.)

## Typical Pipeline Configuration

```
Tika Parser → Chunker 1 → Chunker 2 → Embeddings 1 → Embeddings 2 → Embeddings 3
```
Results in 6 embeddings total. Soon adding OpenSearch sink.

## Module Types

### Currently Working
- `tika-parser` - Document parsing
- `chunker` - Text chunking
- `embedder` - Generate embeddings
- `echo` - Testing
- `test-module` - Testing

### Planned Modules
- Commons Crawl connector
- Gutenberg Project crawler
- AI image capture (video stills → text)
- NLP features (NER, categorization)
- JDBC connector/sink
- S3 sink & protobuf save
- Firehose sink
- Web crawler

## Build & Test Commands

```bash
# Build Docker images
gradle dockerBuild

# Run tests (uses Testcontainers)
gradle test

# Run specific module tests
gradle :yappy-orchestrator:engine-core:test
```

## Development Pain Points

1. **Integration Tests** - Too hard to do simple tasks, brittle build
2. **Test Resources** - Using Micronaut test resources or docker-compose for near-prod validation

## Performance Requirements

- **Throughput**: 100+ docs/second (or better)
- **Document Size**: Any size (generic offering)
- **Latency**: No specific requirements yet

## Multi-Tenancy

- Required for production
- Should be straightforward given near-stateless architecture

## Key Files to Reference

### Planning Documents
- `/ENGINE-REWRITE-PROJECT-PLAN.md` - Current development roadmap
- `/kafka-service-implementation-plan.md` - Kafka service TODOs
- `/kafka-service-refactoring-analysis.md` - Architecture refinements

### Requirements
- `/REQUIREMENTS/01-overview-and-principles.md` - Core architecture
- `/REQUIREMENTS/08-kafka-integration.md` - Kafka design
- `/REQUIREMENTS/13-module-registration-flow.md` - Registration process
- `/REQUIREMENTS/15-grpc-first-implementation.md` - gRPC approach

## Testing Scenarios to Ensure

1. Basic chain: Tika → Chunker → Embeddings → OpenSearch
2. Fan-in/fan-out patterns
3. 100-step pipeline stress test
4. Error handling and recovery
5. Configuration hot-reload

## Important Notes

- **NO Bootstrap Mode** - Removed from architecture
- **NO Hot Reload** in modules - Only configuration updates
- **NO Self-Registration** - Always explicit via CLI
- **NO Business Logic** in engine - Pure orchestration only

## Current Priorities (In Order)

1. Stabilize integration testing environment
2. Get end-to-end pipeline working for confidence
3. Complete engine rewrite per project plan
4. Finish Kafka service refactoring
5. Implement first production pipeline

## Code Style

- Standard Java conventions
- Inline documentation preferred
- No specific style beyond existing codebase
- Keep it simple - if it feels complex, it probably is

## When Working on This Project

1. Check `ENGINE-REWRITE-PROJECT-PLAN.md` for current phase
2. Refer to REQUIREMENTS docs for architectural decisions
3. Use real integration tests (no mocks)
4. Maintain separation between engine and modules
5. Events are for same-process only, gRPC for inter-service
6. Document inline as you code