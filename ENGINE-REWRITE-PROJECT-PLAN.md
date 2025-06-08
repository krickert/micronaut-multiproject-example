# YAPPY Engine Rewrite Project Plan

## Overview
This is a structured plan to build a fresh YAPPY Engine as a pure orchestration layer. We're starting from scratch to avoid the complexity that crept into previous attempts.

## What We Have Working
- ✅ **yappy-consul-config** - Dynamic configuration with Consul watches
- ✅ **yappy-kafka-slot-manager** - Partition management (needs integration)
- ✅ **yappy-module-registration** - CLI for registering modules
- ✅ **tika-parser module** - Document parsing (ready to use)
- ✅ **chunker module** - Text chunking (ready to use)
- ✅ **Other modules** - Embedder, OpenSearch sink, various connectors

## What to Keep From Old Code (as reference only)
- ✅ **DynamicKafkaListener pattern** - Good slot-based partition management
- ✅ **Health monitoring patterns** - Clean reactive interface
- ✅ **Metrics collection** - Micrometer integration
- ✅ **Example JSON configurations** - Great for testing and documentation
- ✅ **Test patterns** - Good test scenarios (before they got complex)
- ❌ **Registration complexity** - Over-engineered, start fresh
- ❌ **Bootstrap process** - Too complex, no bootstrap mode needed
- ❌ **Execution logic** - Old arch too different, rewrite

## Core Principles
1. **Start Fresh** - Don't copy old code, just use as reference
2. **Keep It Simple** - If it feels complex, it probably is
3. **Use What Works** - We have working modules, focus on orchestration
4. **One Step at a Time** - Get gRPC working before adding Kafka
5. **No Over-Engineering** - Simple registration, simple routing

---

## Phase 1: Foundation Setup ✅ DONE
**Goal:** Clean codebase with updated requirements

### Tasks:
- [x] Review and update all REQUIREMENTS documentation
- [x] Remove references to old architecture
- [x] Create navigation index for future development
- [x] Review existing Kafka slot manager code

---

## Phase 2: Clean Up Codebase 🚀 CURRENT
**Goal:** Remove all obsolete code and documentation

### Tasks:
- [ ] Delete obsolete .md files from root directory
- [ ] Remove old engine implementations that don't match new architecture
- [ ] Clean up test files that test removed functionality
- [ ] Verify build still passes after cleanup

### Files to Review for Deletion:
- `current_instructions.md` (replaced by REQUIREMENTS/)
- Any bootstrap-related code (no bootstrap mode)
- Hot reload implementations
- Embedded engine code in modules

---

## Phase 3: Core Engine Components
**Goal:** Implement the pure orchestration engine

### 3.1 Pipeline Engine Core
- [ ] Create `PipelineEngineImpl` with in-memory queues
- [ ] Implement `QueueManager` for bounded queues per step
- [ ] Add worker threads that pull from queues
- [ ] Implement basic backpressure (blocking when queues full)

### 3.2 Service Discovery
- [ ] Implement `ConsulServiceDiscovery` using Micronaut
- [ ] Add connection pooling per module type
- [ ] Implement health-aware instance selection
- [ ] Add circuit breaker for failed instances

### 3.3 Configuration Manager
- [ ] Verify `DynamicConfigurationManager` works as documented
- [ ] Add configuration validation on load
- [ ] Implement configuration change notifications
- [ ] Test automatic routing updates

### 3.4 Message Routing
- [ ] Implement `MessageRoutingServiceImpl`
- [ ] Clone PipeStream for each output (immutable pattern)
- [ ] Add StepExecutionRecord to history
- [ ] Route based on pipeline configuration

---

## Phase 4: Module Registration
**Goal:** Complete registration flow from CLI to Consul

### 4.1 Registration Service
- [ ] Implement engine-side registration service
- [ ] Add module health validation before registration
- [ ] Implement Consul registration with health checks
- [ ] Add proper error handling and logging

### 4.2 CLI Integration
- [ ] Verify CLI can call engine registration endpoint
- [ ] Add timeout and retry logic
- [ ] Implement deregistration command
- [ ] Add query commands for debugging

### 4.3 Health Monitoring
- [ ] Implement continuous health monitoring via Consul
- [ ] Update ServiceAggregatedStatus in KV store
- [ ] Handle instance failures gracefully
- [ ] Add health endpoint for engine itself

---

## Phase 5: Connector Engine
**Goal:** Enable data ingestion into pipelines

### 5.1 Connector Service
- [ ] Implement `ConnectorEngine` gRPC service
- [ ] Add connector mapping configuration support
- [ ] Implement source_identifier routing
- [ ] Add connector response handling

### 5.2 Connector Mappings
- [ ] Store mappings in Consul KV
- [ ] Implement mapping validation
- [ ] Add CLI commands for mapping management
- [ ] Test multiple sources to same pipeline

---

## Phase 6: First Working Pipeline
**Goal:** End-to-end document processing with real modules

### 6.1 Deploy Test Modules
- [ ] Deploy tika-parser module
- [ ] Deploy chunker module
- [ ] Deploy echo module (for testing)
- [ ] Register all modules via CLI

### 6.2 Create Pipeline Configuration
- [ ] Create simple pipeline: connector → tika → chunker → echo
- [ ] Store configuration in Consul
- [ ] Verify configuration loads correctly
- [ ] Test configuration updates

### 6.3 Process Test Documents
- [ ] Submit document via connector
- [ ] Verify document flows through pipeline
- [ ] Check StepExecutionRecords
- [ ] Verify output from echo module

---

## Phase 7: Error Handling & DLQ
**Goal:** Robust error handling with visibility

### 7.1 Retry Logic
- [ ] Implement configurable retry with backoff
- [ ] Add retry metrics
- [ ] Handle different error types appropriately
- [ ] Test retry scenarios

### 7.2 Dead Letter Queue
- [ ] Implement in-memory DLQ per step
- [ ] Add DLQ size limits and overflow handling
- [ ] Create API to view DLQ contents
- [ ] Add manual retry from DLQ

### 7.3 Error Tracking
- [ ] Ensure ErrorData captures all context
- [ ] Add error metrics and alerts
- [ ] Implement error recovery workflows
- [ ] Test various failure scenarios

---

## Phase 8: Monitoring & Observability
**Goal:** Full visibility into system operation

### 8.1 Metrics
- [ ] Integrate Micrometer
- [ ] Add key metrics (throughput, latency, errors)
- [ ] Export to Prometheus
- [ ] Create Grafana dashboards

### 8.2 Logging
- [ ] Structured JSON logging
- [ ] Correlation IDs throughout
- [ ] Log aggregation to OpenSearch
- [ ] Create log analysis dashboards

### 8.3 Tracing
- [ ] Add OpenTelemetry support
- [ ] Trace requests through pipeline
- [ ] Integrate with Jaeger
- [ ] Add trace sampling

---

## Phase 9: Kafka Integration
**Goal:** Add asynchronous processing via Kafka

### 9.1 Kafka Slot Manager Integration
- [ ] Integrate existing KafkaSlotManager
- [ ] Add automatic rebalancing triggers
- [ ] Improve error handling
- [ ] Add metrics for slot distribution

### 9.2 Hybrid Routing
- [ ] Update routing to support Kafka transport
- [ ] Implement Kafka producer in engine
- [ ] Add Kafka consumer with slot management
- [ ] Test mixed gRPC/Kafka pipelines

### 9.3 Topic Management
- [ ] Implement topic creation for pipeline steps
- [ ] Add DLQ topics
- [ ] Verify naming conventions
- [ ] Test cross-pipeline communication

---

## Phase 10: Production Hardening
**Goal:** Prepare for production deployment

### 10.1 Performance
- [ ] Load testing with realistic volumes
- [ ] Optimize connection pooling
- [ ] Tune queue sizes and worker counts
- [ ] Add batching where beneficial

### 10.2 Large Document Support
- [ ] Implement MongoDB integration
- [ ] Add transparent external storage
- [ ] Test with large documents
- [ ] Add cleanup/retention policies

### 10.3 Security (Future)
- [ ] Document security requirements
- [ ] Plan mTLS implementation
- [ ] Design authentication approach
- [ ] Create security testing plan

---

## Success Criteria

### Phase 6 Milestone (First Working Pipeline):
- Document submitted via CLI flows through pipeline
- Each module processes document successfully  
- Execution history shows all steps
- No manual intervention required

### Phase 9 Milestone (Kafka Integration):
- Mixed gRPC/Kafka pipeline works
- Kafka slot manager prevents excess consumers
- Automatic rebalancing on engine changes
- No message loss during rebalancing

### Final Milestone:
- 100+ documents/second throughput
- <100ms p99 latency for gRPC steps
- Zero message loss
- Automatic recovery from failures
- Full observability

---

## Risk Mitigation

1. **Keep It Simple**
   - Don't add features not in plan
   - Resist premature optimization
   - Focus on working software

2. **Test Continuously**
   - Integration tests for each phase
   - No mocks in integration tests
   - Test failure scenarios

3. **Regular Checkpoints**
   - Working software at each phase
   - Document decisions made
   - Update plan based on learnings

---

## Next Steps

1. Complete Phase 2 cleanup
2. Start Phase 3 with PipelineEngineImpl
3. Set up development environment for testing
4. Create simple test modules if needed