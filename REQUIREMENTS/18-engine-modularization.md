# Engine Modularization Strategy

## Why Modularize the Engine

Based on your experience with previous rewrites, modularizing the engine will:
1. **Prevent Code Drift** - Smaller, focused modules are easier to maintain
2. **Improve Testability** - Each module can be tested in isolation
3. **Enable Incremental Changes** - Update one module without touching others
4. **Reduce Complexity** - Clear boundaries between concerns
5. **Support Team Development** - Different teams can own different modules

## Proposed Module Structure

```
yappy-orchestrator/
├── engine-core/              # Core abstractions and interfaces
├── engine-grpc/              # gRPC services and communication
├── engine-kafka/             # Kafka integration and slot management
├── engine-pipeline/          # Pipeline execution logic
├── engine-config/            # Configuration management
├── engine-health/            # Health monitoring and status
├── engine-registration/      # Module registration service
├── engine-connector/         # Connector engine service
└── engine-bootstrap/         # Main application and wiring
```

## Module Descriptions

### 1. engine-core
**Purpose:** Core domain models and interfaces shared by all modules

**Contents:**
- `PipelineEngine` interface
- `MessageRouter` interface
- `ServiceDiscovery` interface
- Domain models (PipeStream, ProcessRequest, etc.)
- Common exceptions
- Core utilities

**Dependencies:** None (only external libs like protobuf)

### 2. engine-grpc
**Purpose:** All gRPC communication logic

**Contents:**
- gRPC client factories
- Connection pool management
- Circuit breaker implementation
- gRPC health checking
- Retry logic
- Channel management

**Dependencies:** engine-core

### 3. engine-kafka
**Purpose:** Kafka integration (can be excluded for gRPC-only mode)

**Contents:**
- KafkaSlotManager integration
- Kafka producers/consumers
- Topic management
- Partition assignment
- Kafka health checks

**Dependencies:** engine-core

### 4. engine-pipeline
**Purpose:** Pipeline execution and message routing

**Contents:**
- `PipelineEngineImpl`
- `MessageRoutingServiceImpl`
- Queue management
- Worker thread pools
- Backpressure handling
- PipeStream cloning/history

**Dependencies:** engine-core, engine-grpc, engine-kafka (optional)

### 5. engine-config
**Purpose:** Configuration management and Consul integration

**Contents:**
- `DynamicConfigurationManager`
- Consul watchers
- Configuration validation
- Variable resolution
- Configuration change notifications

**Dependencies:** engine-core

### 6. engine-health
**Purpose:** Health monitoring and status management

**Contents:**
- Module health monitoring
- ServiceAggregatedStatus management
- Health endpoints
- Metrics collection
- Status aggregation

**Dependencies:** engine-core, engine-config

### 7. engine-registration
**Purpose:** Module registration service

**Contents:**
- Registration gRPC service
- Module validation
- Consul registration
- Deregistration handling
- Registration CLI backend

**Dependencies:** engine-core, engine-grpc, engine-health

### 8. engine-connector
**Purpose:** Connector engine for data ingestion

**Contents:**
- Connector gRPC service
- Source identifier mapping
- Connector routing
- Input validation

**Dependencies:** engine-core, engine-pipeline

### 9. engine-bootstrap
**Purpose:** Main application that wires everything together

**Contents:**
- Main application class
- Dependency injection configuration
- Module activation based on config
- Startup/shutdown orchestration

**Dependencies:** All other modules

## Configuration-Based Module Activation

Enable/disable modules via configuration:

```yaml
yappy:
  engine:
    modules:
      kafka:
        enabled: false  # Start with gRPC-only
      connector:
        enabled: true
      registration:
        enabled: true
```

## Testing Strategy

### 1. Unit Tests per Module
Each module has its own focused unit tests:
- Mock only external dependencies
- Test module in isolation
- Fast execution

### 2. Integration Tests
- `engine-integration-tests/` module
- Tests module interactions
- Uses real implementations
- Can enable/disable modules for different scenarios

### 3. Contract Tests
- Ensure modules respect interfaces
- Catch breaking changes early
- Run on every build

## Benefits of This Structure

### 1. Clear Boundaries
```java
// engine-pipeline doesn't know about gRPC details
public class PipelineEngineImpl {
    private final MessageRouter router;  // Interface from core
    private final ServiceDiscovery discovery;  // Interface from core
    
    // Just orchestration logic, no transport details
}
```

### 2. Easy Testing
```java
// Test pipeline without gRPC or Kafka
@Test
void testPipelineRouting() {
    MessageRouter mockRouter = mock(MessageRouter.class);
    ServiceDiscovery mockDiscovery = mock(ServiceDiscovery.class);
    
    PipelineEngine engine = new PipelineEngineImpl(mockRouter, mockDiscovery);
    // Test pure orchestration logic
}
```

### 3. Incremental Development
- Start with engine-core, engine-grpc, engine-pipeline
- Add engine-kafka later
- Each module can evolve independently

### 4. Team Scalability
- Frontend team owns engine-connector
- Platform team owns engine-kafka
- Core team owns engine-pipeline
- Clear ownership and responsibilities

## Migration Path

1. **Phase 1:** Create module structure with existing code
2. **Phase 2:** Move classes to appropriate modules
3. **Phase 3:** Clean up dependencies
4. **Phase 4:** Add module-specific tests
5. **Phase 5:** Enable/disable modules via config

## Example: Adding a New Transport

To add a new transport (e.g., RabbitMQ):
1. Create `engine-rabbitmq` module
2. Implement interfaces from `engine-core`
3. Update `engine-bootstrap` to wire it in
4. No changes to pipeline or other modules!

## Dependency Rules

1. **Core has no dependencies** on other engine modules
2. **Modules depend only on core** and external libs
3. **Bootstrap depends on all** modules
4. **No circular dependencies** between modules
5. **Transport modules (gRPC, Kafka) are optional**

This modular structure will prevent the code drift and test degradation that plagued previous attempts.