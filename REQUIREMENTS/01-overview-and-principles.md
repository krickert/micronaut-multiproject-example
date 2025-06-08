# YAPPY Engine: Overview and Foundational Principles

## Project Overview

YAPPY Engine is a pure orchestration layer for distributed data processing pipelines. It coordinates simple gRPC services (modules) that process data through configurable pipeline graphs. The engine handles all infrastructure complexity - service discovery via Consul, message routing via gRPC or Kafka, and pipeline configuration management - while modules focus solely on their business logic.

## Architecture Diagram

```mermaid
graph TB
    subgraph "Data Sources"
        S3[S3 Connector]
        API[API Connector]
        JDBC[JDBC Crawler]
    end

    subgraph "YAPPY Engine - Orchestration Layer"
        CE[Connector Engine<br/>gRPC Service]
        PE[Pipeline Engine<br/>Core Orchestrator]
        KSM[Kafka Slot Manager]
        REG[Registration Service]
        CM[Config Manager]
        
        CE --> PE
        KSM --> PE
        REG --> CM
        CM --> PE
    end

    subgraph "Infrastructure"
        CONSUL[Consul<br/>Service Discovery & Config]
        KAFKA[Kafka<br/>Message Bus]
        REG --> CONSUL
        CM --> CONSUL
        PE --> KAFKA
        KSM --> KAFKA
    end

    subgraph "Processing Modules - Pure gRPC Services"
        TIKA[Tika Parser<br/>Module]
        CHUNK[Chunker<br/>Module]
        EMBED1[Embedder 1<br/>Module]
        EMBED2[Embedder 2<br/>Module]
    end

    subgraph "Sinks"
        OS[OpenSearch<br/>Sink]
        DB[Database<br/>Sink]
    end

    S3 -->|gRPC| CE
    API -->|gRPC| CE
    JDBC -->|gRPC| CE
    
    PE -->|gRPC| TIKA
    PE -->|gRPC| CHUNK
    PE -->|gRPC| EMBED1
    PE -->|gRPC| EMBED2
    PE -->|gRPC| OS
    PE -->|gRPC| DB

    CONSUL -.->|Service Discovery| PE
    PE -.->|Route Decision| KAFKA

    style PE fill:#f9f,stroke:#333,stroke-width:4px
    style CONSUL fill:#9f9,stroke:#333,stroke-width:2px
    style KAFKA fill:#99f,stroke:#333,stroke-width:2px
```

## Foundational Principles

### User-Centric Design & Operational Excellence

The YAPPY Engine prioritizes:

1. **Easy Setup and Configuration** - Simple, out-of-the-box configuration bootstrapping experience
2. **Comprehensive Monitoring** - Detailed status reporting and health checking
3. **Robust Service Lifecycle Management** - Clean registration, health monitoring, and deregistration
4. **Flexible Deployment Models** - Support for both tightly-coupled and distributed deployments
5. **Language Agnostic Module Support** - Modules can be written in any language that supports gRPC

### Core Architecture Principles

1. **Pure Orchestration Layer** - The engine contains no business logic, only routing and coordination
2. **Simple gRPC Modules** - Modules implement only `ProcessData`, `GetServiceRegistration`, and standard gRPC health check
3. **Language Agnostic** - Any language that supports gRPC can implement a module
4. **Configuration-Driven Routing** - All message routing decisions come from pipeline configuration
5. **Explicit Registration** - CI/CD must explicitly register modules using the CLI with engine endpoint
6. **No Infrastructure Awareness** - Modules know nothing about Consul, Kafka, or the orchestration layer

### Operational Principles

1. **No Silent Failures** - All errors are logged and tracked in service status
2. **Graceful Degradation** - The system continues operating even with partial failures
3. **Self-Healing** - Automatic recovery from transient failures
4. **Observable by Default** - Built-in metrics, tracing, and logging
5. **Production-Ready** - Designed for real-world deployment scenarios

## System Components

### Core Components

- **YAPPY Engine** - The orchestration engine that manages pipeline execution
- **Consul** - Service discovery, configuration storage, and health checking
- **Kafka** - Message bus for asynchronous pipeline steps
- **Schema Registry** - Protobuf schema management (Apicurio or AWS Glue)

### Module Types

All modules are simple gRPC services that implement the `PipeStepProcessor` interface. From the module's perspective, there is no difference between types - they all just process data. The engine determines their role based on pipeline configuration:

- **Connectors** - Entry points that feed data into pipelines (S3, web crawlers, JDBC, etc.)
- **Processors** - Transform data (parsing, chunking, embedding, etc.)
- **Sinks** - Terminal steps that output data (OpenSearch, databases, etc.)

The same module can be reused multiple times in a pipeline with different configurations. For example, a chunker module could be used twice with different chunk sizes, or an embedder could be used with different models.

## Key Design Decisions

1. **gRPC for Synchronous Communication** - Type-safe, efficient inter-service communication
2. **Kafka for Asynchronous Processing** - Reliable, scalable message passing
3. **Protobuf for Data Serialization** - Schema evolution and cross-language support
4. **Consul for Service Coordination** - Dynamic configuration and service discovery
5. **Micronaut Framework** - Modern, cloud-native application framework

## Message Flow Example

Here's how a document flows through the system:

```mermaid
sequenceDiagram
    participant C as Connector Client
    participant CE as Connector Engine
    participant PE as Pipeline Engine
    participant CON as Consul
    participant M1 as Tika Parser Module
    participant M2 as Chunker Module
    participant K as Kafka
    participant S as OpenSearch Sink

    C->>CE: Submit document (source_id="s3-prod")
    CE->>CON: Lookup pipeline for source_id
    CON-->>CE: Pipeline="doc-processing", step="parse"
    CE->>PE: Create PipeStream with target_step="parse"
    
    PE->>CON: Lookup "parse" step config
    CON-->>PE: Module="tika-parser", config={...}
    PE->>CON: Discover healthy "tika-parser" instances
    CON-->>PE: Instance at 10.0.1.5:50051
    
    PE->>M1: ProcessData(doc, config)
    M1-->>PE: ProcessResponse(parsed_doc)
    
    PE->>CON: Get next steps for "parse"
    CON-->>PE: Next=["chunk"]
    
    alt Async via Kafka
        PE->>K: Publish to "chunk" topic
        K-->>PE: Consume from "chunk" topic
    else Sync via gRPC
        PE->>M2: ProcessData(parsed_doc, chunk_config)
        M2-->>PE: ProcessResponse(chunks[])
    end
    
    Note over PE: Similar flow for embeddings and sink
```

## Development Philosophy

1. **Clean Architecture** - Clear separation of concerns and dependencies
2. **100% Real Integration Tests** - No mocks or fake implementations in integration tests
3. **Methodical Development** - Fix one issue at a time, verify no regressions
4. **Comprehensive Documentation** - Every component and decision is documented
5. **Future-Proof Design** - Extensible architecture that can grow with requirements