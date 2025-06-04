# Next Steps for YAPPY Containerization

## Current Status




#### Existing APIs:
- `/api/setup/*` - Cluster management (AdminSetupController)
- `/api/status/*` - Engine status (AdminStatusController)
- `/api/admin/kafka/*` - Kafka management (AdminKafkaController)

#### APIs to Add/Verify:
1. **Module Management APIs** ✅ COMPLETED:
   - `GET /api/admin/modules/definitions` - List available module definitions from PipelineModuleMap ✅
   - `GET /api/admin/modules/definitions/{moduleId}` - Get specific module definition ✅
   - `POST /api/admin/modules/definitions` - Create/update module definition ✅
   - `DELETE /api/admin/modules/definitions/{moduleId}` - Delete module definition ✅
   - `GET /api/admin/modules/status` - Show module registration status in Consul ✅
   - All integration tests passing without mocks ✅

2. **Pipeline Configuration APIs**:
   - `GET /api/admin/pipelines` - List all pipelines in current cluster
   - `GET /api/admin/pipelines/{pipelineName}` - Get specific pipeline config
   - `POST /api/admin/pipelines` - Create new pipeline
   - `PUT /api/admin/pipelines/{pipelineName}` - Update pipeline
   - `DELETE /api/admin/pipelines/{pipelineName}` - Delete pipeline

3. **Schema Management APIs**:
   - `GET /api/admin/schemas` - List all schemas
   - `POST /api/admin/schemas` - Register new schema
   - `GET /api/admin/schemas/{schemaId}` - Get specific schema

### Step 4: Create Infrastructure Setup Test

Create a test that validates the entire infrastructure setup:

1. **Test Class**: `TikaChunkerEmbedderFullIntegrationTest.java`
2. **Test Steps**:
   - Start seed engine with `@MicronautTest`
   - Use admin APIs to configure cluster
   - Start module containers (Docker or separate processes)
   - Register modules in Consul
   - Create pipeline configuration
   - Process test documents
   - Verify output

### Step 5: Implement Three-Container Test

As described in current_instructions.md:
- Container 1: Engine + Tika Parser
- Container 2: Engine + Chunker
- Container 3: Engine + Embedder

Test document flow through all three containers.

## Implementation Priority

1. **First**: Verify/implement missing admin APIs (Step 3)
2. **Second**: Create infrastructure setup test (Step 4)
3. **Third**: Implement three-container integration test (Step 5)

## Technical Considerations

### For Multi-Container Testing:
1. Use Docker Compose or Testcontainers for container orchestration
2. Each container runs yappy-engine with different module configurations
3. Use environment variables to specify which module to co-locate
4. Ensure Consul, Kafka, and Apicurio are shared across all containers

### For Admin APIs:
1. Use existing `ConsulBusinessOperationsService` for Consul operations
2. Follow existing patterns in AdminSetupController
3. Add proper validation and error handling
4. Include Swagger/OpenAPI annotations

### For Integration Tests:
1. Use `ProtobufTestDataHelper` for test data
2. Implement proper cleanup in `@AfterEach` methods
3. Use timeouts for async operations
4. Verify both happy path and error scenarios

## Next Session Setup

To continue in a new session (e.g., on Linux for multi-arch builds):

1. Review this file and current_instructions.md
2. Check container registry: `curl http://nas:5000/v2/_catalog | jq`
3. Review existing admin controllers in yappy-engine
4. Continue with Step 3 implementation

## Notes
- All containers use Eclipse Temurin 21 JRE Alpine
- Embedder has architecture-specific dependencies (CUDA for AMD64)
- Multi-arch build may require Linux environment
- NAS registry is at `nas:5000` (will be `nas.rokkon.com` with HTTPS later)