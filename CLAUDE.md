# YAPPY Engine - Claude AI Context Document

## 🚨 CRITICAL: Integration Testing & Module Independence

### Why Integration Testing is Everything
YAPPY's architecture demands **100% real integration tests** because:
- Modules are **completely independent services** (often in different languages)
- The engine is a **pure orchestration layer** with complex distributed interactions
- Mocking these interactions leads to false confidence and production failures
- **Current Issue**: Found fundamental flaws in integration testing approach - fixing this is TOP PRIORITY

### Module Independence is Sacred
Modules must remain **absolutely independent** because:
- **Multi-language ecosystem**: Modules will be written in Python, Go, Rust, Node.js, etc.
- **Zero infrastructure knowledge**: Modules only know gRPC - nothing about Consul, Kafka, or YAPPY
- **Simple contract**: Modules implement only 3 methods: `ProcessData`, `GetServiceRegistration`, `health`
- **Deployment flexibility**: Modules can be deployed anywhere, scaled independently

### Why YAPPY Manages Consul for Modules
The engine handles ALL Consul interactions because:
1. **Language barrier**: Most modules are non-Java and shouldn't need Consul client libraries
2. **Complexity hiding**: Module developers focus on business logic, not service discovery
3. **Consistent registration**: Engine ensures proper health checks, metadata, and naming
4. **Central control**: Engine can update/deregister modules without module cooperation
5. **Security**: Modules never need Consul tokens or infrastructure access

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

## ⚠️ Micronaut Gotchas That Will Bite You

### 1. Dependency Injection Pitfalls
- **@Singleton vs @Context**: Use `@Singleton` for stateless services, `@Context` sparingly
- **Constructor Injection Only**: Field injection doesn't work reliably with AOT
- **Interface Implementations**: Always use `@Singleton(MyInterface.class)` or `@Replaces`
- **Circular Dependencies**: Micronaut won't save you - use `@Context` or refactor

### 2. Configuration Headaches
- **@ConfigurationProperties**: MUST have public setters or use records
- **Property Sources Order**: application.yml < environment < system properties < Consul
- **@Value with defaults**: Use `@Value("${my.prop:defaultValue}")` syntax
- **List/Map injection**: Requires specific YAML structure - test thoroughly

### 3. Reactive Programming Traps
- **Blocking in Reactive**: NEVER use `.block()` in reactive chains (except tests)
- **Thread Pool Starvation**: Default event loop is small - configure for your load
- **Error Propagation**: Reactive errors need explicit handling or they're swallowed
- **Context Propagation**: MDC and security context need special handling

### 4. Testing Nightmares
- **@MicronautTest**: Starts full context - expensive for unit tests
- **Testcontainers Integration**: Use `@TestPropertyProvider` for dynamic ports
- **Mock Beans**: Use `@MockBean(MyService.class)` not Mockito directly
- **Context Refresh**: Some config changes require context restart

### 5. gRPC Specific Issues
- **Service Discovery**: gRPC services need special Consul metadata
- **Health Checks**: Implement both gRPC health and HTTP health endpoints
- **Interceptors**: Order matters - security before logging before metrics
- **Deadlines**: Set reasonable deadlines or suffer infinite hangs

### 6. Common Build/Runtime Issues
- **AOT Compilation**: Reflection-based code needs hints
- **Native Image**: Many libraries need configuration files
- **Classpath Scanning**: Expensive at startup - be specific with packages
- **JAR Size**: Shade/shadow carefully - Micronaut JARs get huge

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

## Integration Testing Deep Dive

### Why Integration Tests Are Hard (But Critical)
1. **Distributed System Reality**: 
   - Multiple services in different languages
   - Consul for service discovery
   - Kafka for async messaging
   - Network failures, timeouts, retries
   
2. **Current Pain Points**:
   - Tests are brittle due to timing issues
   - Hard to set up test scenarios
   - Testcontainers startup time is significant
   - Port conflicts and resource cleanup issues

3. **Best Practices for YAPPY Integration Tests**:
   ```java
   @MicronautTest
   @TestPropertyProvider(MyTestPropertyProvider.class) // Dynamic ports
   class PipelineIntegrationTest {
       // ⚠️ ALL CONTAINERS MANAGED BY TEST RESOURCES ⚠️
       // NEVER create Testcontainers directly - use test resources
       // NEVER "quickly" spin up a container for testing
       // Test resources handles lifecycle, cleanup, and configuration
       
       // Real services via test resources:
       // - Consul (via test resources)
       // - Kafka (via test resources)
       // - Module containers (via test resources)
       // - OpenSearch (via test resources)
   }
   ```
   
   **Why Test Resources Only**:
   - Consistent container lifecycle management
   - Proper cleanup between tests
   - Shared containers for faster tests
   - Automatic port management
   - Configuration injection into Micronaut context

4. **Common Integration Test Failures**:
   - **Timing**: Use Awaitility, not Thread.sleep()
   - **Cleanup**: Always clean Consul KV between tests
   - **Ports**: Let Testcontainers assign random ports
   - **Health Checks**: Wait for services to be healthy, not just started

### Module Integration Contract
Every module MUST:
1. Implement the gRPC service definition exactly
2. Return proper health check responses
3. Handle errors gracefully (no crashes)
4. Process PipeStream without modification (immutable)
5. Include correlation IDs in all logs

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

## Module Development Guidelines (For Non-Java Developers)

### What a Module Sees
```protobuf
service YappyModule {
  rpc ProcessData(PipeStream) returns (ProcessDataResponse);
  rpc GetServiceRegistration(Empty) returns (ServiceRegistration);
  rpc Check(HealthCheckRequest) returns (HealthCheckResponse); // gRPC health
}
```
That's it. No Consul. No Kafka. No configuration files. Just gRPC.

### Module Lifecycle from Module's Perspective
1. **Startup**: Module starts, listens on a port
2. **Registration**: YAPPY calls `GetServiceRegistration()` to learn about the module
3. **Processing**: YAPPY sends `ProcessData()` requests with data to process
4. **Health**: YAPPY periodically calls `Check()` to verify module health
5. **Shutdown**: Module shuts down cleanly when terminated

### What YAPPY Handles For You
- Service discovery (Consul registration)
- Load balancing across module instances
- Health monitoring and circuit breaking
- Configuration management
- Message routing between modules
- Error handling and retries
- Metrics and monitoring

### Module Best Practices
1. **Stateless**: Modules should be completely stateless
2. **Idempotent**: Same input should always produce same output
3. **Fast Startup**: Modules will be scaled up/down frequently
4. **Graceful Shutdown**: Handle SIGTERM properly
5. **Clear Errors**: Return descriptive error messages

## When Working on This Project

1. Check `ENGINE-REWRITE-PROJECT-PLAN.md` for current phase
2. Refer to REQUIREMENTS docs for architectural decisions
3. Use real integration tests (no mocks)
4. Maintain separation between engine and modules
5. Events are for same-process only, gRPC for inter-service
6. Document inline as you code
7. **Remember**: Modules know NOTHING about YAPPY internals