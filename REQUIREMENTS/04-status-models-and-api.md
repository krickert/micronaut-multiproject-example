# Status Models, API Definitions, and Health Checks

## Status Models

### ServiceOperationalStatus Enum

Comprehensive set of states representing the lifecycle and health of a service:

```java
public enum ServiceOperationalStatus {
    UNKNOWN,                       // Initial state or status cannot be determined
    DEFINED,                       // Service is defined in configuration but not yet initialized
    INITIALIZING,                  // Service is starting up
    AWAITING_HEALTHY_REGISTRATION, // Service started but waiting for health checks to pass
    ACTIVE_HEALTHY,               // Service is running and healthy
    ACTIVE_PROXYING,              // Service is unavailable locally, proxying to remote instance
    DEGRADED_OPERATIONAL,         // Service is running but with reduced capacity
    CONFIGURATION_ERROR,          // Service has configuration issues (BAD_SCHEMA, BAD_CONFIG)
    UNAVAILABLE,                  // Service is not reachable
    UPGRADING,                    // Service is being upgraded
    STOPPED                       // Service has been intentionally stopped
}
```

### ServiceAggregatedStatus Model

Complete status information for a service, aggregating data from multiple sources:

```java
public record ServiceAggregatedStatus(
    String serviceName,                    // Logical service name from pipeline config
    ServiceOperationalStatus operationalStatus, // Current operational state
    String statusDetail,                   // Human-readable status description
    long lastCheckedByEngineMillis,        // Timestamp of last status check
    int totalInstancesConsul,              // Total instances registered in Consul
    int healthyInstancesConsul,            // Number of healthy instances
    boolean isLocalInstanceActive,         // Is there a local instance running
    String activeLocalInstanceId,          // ID of the local instance if active
    boolean isProxying,                    // Is traffic being proxied
    String proxyTargetInstanceId,          // Target instance ID if proxying
    boolean isUsingStaleClusterConfig,     // Is config outdated
    String activeClusterConfigVersion,     // Version of active configuration
    String reportedModuleConfigDigest,     // Hash of module's configuration
    List<String> errorMessages,            // List of current errors
    Map<String, Object> additionalAttributes // Extensible attributes
) {}
```

## API Definitions

### gRPC Services

#### BootstrapConfigService

Handles initial engine setup and cluster management:

```proto
syntax = "proto3";
package com.krickert.search.orchestrator;

service BootstrapConfigService {
  // Consul connection management
  rpc SetConsulConfiguration(ConsulConfigDetails) returns (ConsulConnectionStatus);
  
  // Cluster management
  rpc ListAvailableClusters(Empty) returns (ClusterList);
  rpc SelectExistingCluster(ClusterSelection) returns (OperationStatus);
  rpc CreateNewCluster(NewClusterDetails) returns (ClusterCreationStatus);
}

message ConsulConfigDetails {
  string host = 1;
  int32 port = 2;
  optional string acl_token = 3;
  optional string datacenter = 4;
  map<string, string> additional_config = 5;
}

message ConsulConnectionStatus {
  bool success = 1;
  string message = 2;
  optional string error_detail = 3;
}

message ClusterList {
  repeated ClusterInfo clusters = 1;
}

message ClusterInfo {
  string name = 1;
  string created_timestamp = 2;
  int32 module_count = 3;
  string config_version = 4;
}

message ClusterSelection {
  string cluster_name = 1;
}

message NewClusterDetails {
  string cluster_name = 1;
  optional string description = 2;
  map<string, string> initial_config = 3;
}

message ClusterCreationStatus {
  bool success = 1;
  string cluster_name = 2;
  string message = 3;
  optional string error_detail = 4;
}

message OperationStatus {
  bool success = 1;
  string message = 2;
  optional string error_detail = 3;
}

message Empty {}
```

### REST APIs

#### Status Endpoints

**Service Status List**
```
GET /api/status/services
Response: 200 OK
Content-Type: application/json

[
  {
    "serviceName": "chunker-service",
    "operationalStatus": "ACTIVE_HEALTHY",
    "statusDetail": "Processing messages normally",
    "lastCheckedByEngineMillis": 1705320000000,
    "totalInstancesConsul": 3,
    "healthyInstancesConsul": 3,
    "isLocalInstanceActive": true,
    "activeLocalInstanceId": "chunker-abc123",
    "isProxying": false,
    "proxyTargetInstanceId": null,
    "isUsingStaleClusterConfig": false,
    "activeClusterConfigVersion": "v1.2.3",
    "reportedModuleConfigDigest": "sha256:abcd1234",
    "errorMessages": [],
    "additionalAttributes": {
      "messagesProcessed": 15000,
      "averageLatencyMs": 23
    }
  }
]
```

**Individual Service Status**
```
GET /api/status/services/{serviceName}
Response: 200 OK | 404 Not Found
Content-Type: application/json
```

**Cluster Status**
```
GET /api/status/cluster
Response: 200 OK
Content-Type: application/json

{
  "engineId": "engine-prod-001",
  "clusterName": "production-cluster",
  "engineHealth": "HEALTHY",
  "configurationStatus": {
    "version": "v1.2.3",
    "lastUpdated": "2024-01-15T10:30:00Z",
    "isStale": false
  },
  "consulStatus": {
    "connected": true,
    "datacenter": "dc1",
    "leader": "consul-server-1"
  },
  "kafkaStatus": {
    "connected": true,
    "brokerCount": 3,
    "consumerGroups": ["pipeline-1", "pipeline-2"]
  }
}
```

#### Configuration Endpoints

**Current Cluster Configuration**
```
GET /api/config/cluster
Response: 200 OK
Content-Type: application/json

{
  "clusterName": "production-cluster",
  "pipelineGraphConfig": { ... },
  "pipelineModuleMap": { ... },
  "allowedKafkaTopics": [ ... ],
  "allowedGrpcServices": [ ... ],
  "whitelistConfig": { ... }
}
```

#### Setup Endpoints

**Consul Configuration**
```
POST /setup/consul
Content-Type: application/json
Request:
{
  "host": "consul.example.com",
  "port": 8500,
  "aclToken": "secret-token",
  "datacenter": "dc1"
}

Response: 200 OK | 400 Bad Request
{
  "success": true,
  "message": "Successfully connected to Consul",
  "consulVersion": "1.17.0"
}
```

**List Available Clusters**
```
GET /setup/clusters
Response: 200 OK
Content-Type: application/json

{
  "clusters": [
    {
      "name": "production-cluster",
      "createdTimestamp": "2024-01-01T00:00:00Z",
      "moduleCount": 15,
      "configVersion": "v1.2.3"
    }
  ]
}
```

**Select Cluster**
```
POST /setup/cluster/select
Content-Type: application/json
Request:
{
  "clusterName": "production-cluster"
}

Response: 200 OK | 404 Not Found
{
  "success": true,
  "message": "Successfully joined cluster: production-cluster"
}
```

**Create New Cluster**
```
POST /setup/cluster/create
Content-Type: application/json
Request:
{
  "clusterName": "new-cluster",
  "description": "Development cluster",
  "initialConfig": {
    "environment": "development"
  }
}

Response: 201 Created | 409 Conflict
{
  "success": true,
  "clusterName": "new-cluster",
  "message": "Cluster created with seed configuration"
}
```

## Health Check Standards

### Module Health Requirements

All YAPPY modules must implement standardized health checks:

#### HTTP Health Endpoint

```
GET /health
Response: 200 OK | 503 Service Unavailable
Content-Type: application/json

{
  "status": "UP",
  "details": {
    "configuration": {
      "status": "UP",
      "schemaValid": true,
      "configDigest": "sha256:abcd1234"
    },
    "grpc": {
      "status": "UP",
      "port": 50051,
      "activeConnections": 5
    },
    "dependencies": {
      "kafka": {
        "status": "UP",
        "brokers": ["kafka1:9092", "kafka2:9092"]
      }
    }
  }
}
```

#### gRPC Health Check Protocol

Using standard gRPC health checking:

```proto
syntax = "proto3";
package grpc.health.v1;

service Health {
  rpc Check(HealthCheckRequest) returns (HealthCheckResponse);
  rpc Watch(HealthCheckRequest) returns (stream HealthCheckResponse);
}

message HealthCheckRequest {
  string service = 1;
}

message HealthCheckResponse {
  enum ServingStatus {
    UNKNOWN = 0;
    SERVING = 1;
    NOT_SERVING = 2;
    SERVICE_UNKNOWN = 3;
  }
  ServingStatus status = 1;
}
```

### Engine Health Indicator

The YAPPY Engine implements a comprehensive health check:

```java
@Singleton
public class EngineHealthIndicator implements HealthIndicator {
    
    @Override
    public Publisher<HealthResult> getResult() {
        return Mono.fromCallable(() -> {
            Map<String, Object> details = new HashMap<>();
            
            // Check Consul connectivity
            details.put("consul", checkConsulHealth());
            
            // Check configuration status
            details.put("configuration", checkConfigurationHealth());
            
            // Check critical services
            details.put("criticalServices", checkCriticalServicesHealth());
            
            // Determine overall health
            HealthStatus status = calculateOverallHealth(details);
            
            return HealthResult.builder(status)
                .details(details)
                .build();
        });
    }
}
```

### Health Check Types

1. **HTTP Health Checks**
   - Interval: 10 seconds
   - Timeout: 5 seconds
   - Deregister after: 30 seconds

2. **gRPC Health Checks**
   - Use gRPC health checking protocol
   - Stream-based for real-time updates

3. **TCP Health Checks**
   - Simple port connectivity check
   - Used as fallback when HTTP/gRPC unavailable

4. **TTL Health Checks**
   - Module must update within TTL window
   - Used for batch processors

### Configuration Validation in Health Checks

Modules must validate their configuration as part of health checks:

1. **Schema Validation**
   - Validate `customConfigJson` against declared schema
   - Report `NOT_SERVING` if validation fails

2. **Dependency Checks**
   - Verify required services are accessible
   - Check Kafka connectivity if used

3. **Resource Availability**
   - Verify disk space, memory, file handles
   - Report degraded status if resources low

## Error Response Standards

All APIs use consistent error responses:

```json
{
  "error": {
    "code": "CLUSTER_NOT_FOUND",
    "message": "Cluster 'unknown-cluster' does not exist",
    "details": {
      "availableClusters": ["cluster1", "cluster2"],
      "timestamp": "2024-01-15T10:30:00Z"
    }
  }
}
```

Error codes follow pattern: `DOMAIN_SPECIFIC_ERROR`
- `CONSUL_CONNECTION_FAILED`
- `CLUSTER_NOT_FOUND`
- `INVALID_CONFIGURATION`
- `SERVICE_UNAVAILABLE`
- `REGISTRATION_FAILED`