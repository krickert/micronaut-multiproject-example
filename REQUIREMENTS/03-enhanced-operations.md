# Enhanced Engine Operations within a Cluster

*This document describes the operational aspects of the YAPPY Engine as a pure orchestration layer coordinating simple gRPC services (modules) registered in Consul.*

## Phase B.1: Define Core Status Models & Schema

### 1. ServiceOperationalStatus Enum

Define the operational states for modules:

```java
public enum ServiceOperationalStatus {
    UNKNOWN,        // Status not determined
    REGISTERED,     // Module registered in Consul
    HEALTHY,        // Module responding to health checks
    UNHEALTHY,      // Module failing health checks  
    UNREGISTERED    // Module removed from Consul
}
```

Note: When Kafka integration is added, additional states may be introduced to track Kafka-specific statuses.

### 2. ServiceAggregatedStatus Record

Status information for each module:

```java
public record ServiceAggregatedStatus(
    String serviceName,
    ServiceOperationalStatus operationalStatus,
    String statusDetail,
    long lastCheckedByEngineMillis,
    int totalInstancesConsul,
    int healthyInstancesConsul,
    String activeClusterConfigVersion,
    String reportedModuleConfigDigest,
    List<String> errorMessages,
    Map<String, Object> additionalAttributes
) {}
```

### 3. JSON Schema for ServiceAggregatedStatus

The record will be serialized to JSON for storage in Consul KV. Schema documentation will be generated for API consumers.

## Phase B.2: Implement Engine Logic for Status Management

### 1. Status Aggregation Component

Develop an engine component that:

- **Monitors Consul service health** via `ConsulBusinessOperationsService`
- **Queries all registered service instances** from Consul
- **Tracks PipelineClusterConfig version** from `DynamicConfigurationManager`
- **Detects configuration errors:**
  - `BAD_SCHEMA`: Schema registry issues, unparsable schemas
  - `BAD_CONFIG`: Module config fails validation against schema
- **Calculates ServiceOperationalStatus** for each service in `PipelineClusterConfig.pipelineModuleMap`
- **Populates ServiceAggregatedStatus** with comprehensive status information

### 2. Consul KV Status Updates

- **Update Frequency:** Periodically or event-driven on relevant changes
- **Storage Path:** `yappy/status/services/{logicalServiceName}`
- **Method:** `ConsulBusinessOperationsService.putValue()`
- **Format:** JSON serialized `ServiceAggregatedStatus`

## Phase B.3: Engine Startup Requirements

### 1. No Bootstrap Mode

The engine requires Consul to be configured before startup:

1. **Consul Required:** Engine fails to start if Consul connection not configured
2. **Configuration Required:** Pipeline configurations must exist in Consul
3. **No Self-Healing:** Engine does not create default configurations
4. **Admin Tools:** Use CLI or admin UI to create initial configurations

### 2. Engine-Managed Module Registration

**Important:** The engine handles all Consul registration on behalf of modules. Modules are simple gRPC services with no knowledge of Consul.

**CI/CD Registration Flow:**

1. **Module Deployment**
   - CI/CD deploys module as a simple gRPC service
   - Module starts with only its gRPC endpoints

2. **CI/CD Calls Registration CLI**
   - CLI invoked with module details:
     ```bash
     yappy-registration-cli register \
       --module-host <IP> \
       --module-port <PORT> \
       --engine-endpoint <ENGINE_URL> \
       --health-check-type <HTTP|GRPC|TCP> \
       --health-check-path </health>
     ```

3. **Engine Registration Process**
   - CLI calls engine's registration service
   - Engine validates module health
   - Engine registers module in Consul
   - Returns service ID to CLI

4. **Consul Registration Details**
   ```java
   Registration registration = new Registration();
   registration.setId(generateServiceId());
   registration.setName(serviceName);
   registration.setAddress(moduleAddress);
   registration.setPort(modulePort);
   registration.setTags(Arrays.asList(
       "yappy-service-name=" + serviceName,
       "yappy-version=" + version,
       "yappy-config-digest=" + configDigest
   ));
   registration.setCheck(createHealthCheck());
   ```

5. **Deregistration**
   - CI/CD calls CLI during shutdown
   - Engine deregisters from Consul

### 3. Configuration-Driven Routing

The engine routes messages based on pipeline configuration:

1. **Service Discovery**
   - Modules discovered via Consul using service name from config
   - Micronaut service discovery handles connection pooling
   - Consul provides healthy instance selection

2. **Routing Decisions**
   - Pipeline configuration defines all routing paths
   - No dynamic routing decisions by engine
   - Engine simply follows configured outputs for each step

3. **Connection Management**
   - Separate connection pool per module type
   - Micronaut handles failover automatically
   - Consul health checks ensure only healthy instances used

## Phase B.4: Implement Health Check Integration

### 1. Engine Health Indicator

Create Micronaut `HealthIndicator` that verifies:

- **Consul Connectivity**
  - Can reach Consul
  - ACL token (if used) is valid

- **Configuration Manager Status**
  - `PipelineClusterConfig` is loaded
  - Configuration is valid
  - Not unexpectedly stale

- **Critical Service Health**
  - Read from `yappy/status/services/*` KV
  - Aggregate health of services required by active pipelines

### 2. Module Health Endpoint Requirements

**Mandate for all YAPPY modules:**

- **Expose Standard Health Endpoint**
  - HTTP: `GET /health`
  - gRPC: Health Checking Protocol

- **Include Configuration Status**
  - Validate `customConfigJson` against declared schema
  - Report unhealthy if configuration is invalid

- **Engine Consumption**
  - Status aggregation uses health check results
  - Determines `ServiceOperationalStatus`

## Phase B.5: Expose Status and Configuration via API

### REST API Endpoints

1. **Service Status Endpoints**
   - `GET /api/status/services` - List all service statuses
   - `GET /api/status/services/{serviceName}` - Get specific service status

2. **Cluster Status Endpoints**
   - `GET /api/status/cluster` - Overall engine health and configuration status
   - `GET /api/config/cluster` - Current `PipelineClusterConfig`

3. **Proxied Consul Endpoints (Optional)**
   - `GET /api/consul/services` - List Consul services
   - `GET /api/consul/services/{serviceName}/instances` - Service instances
   - `GET /api/consul/services/{serviceName}/health` - Service health

### Response Examples

**Service Status Response:**
```json
{
  "serviceName": "chunker-service",
  "operationalStatus": "HEALTHY",
  "statusDetail": "All instances healthy",
  "lastCheckedByEngineMillis": 1705320000000,
  "totalInstancesConsul": 3,
  "healthyInstancesConsul": 3,
  "activeClusterConfigVersion": "v1.2.3",
  "reportedModuleConfigDigest": "sha256:abcd1234",
  "errorMessages": [],
  "additionalAttributes": {
    "messagesProcessed": 15000,
    "averageLatencyMs": 23
  }
}
```

## Phase B.6: Testing for Enhanced Operations

### 1. Unit Tests

Mock dependencies to test:
- Status calculation logic
- Registration decision making
- Error handling scenarios
- State transitions

### 2. Integration Tests

Using MicronautTest with Testcontainers:

- **Bootstrap Cluster Refinement**
  - Test engine seeding empty cluster config
  - Verify self-healing behavior

- **Module Registration Flow**
  - Test registration of co-located test module
  - Verify health check integration

- **Status Updates**
  - Simulate healthy/unhealthy modules
  - Verify correct `ServiceAggregatedStatus` in KV

- **API Testing**
  - Test all REST endpoints
  - Verify response formats
  - Test error conditions