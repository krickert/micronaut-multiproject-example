# Engine Configuration and Startup

This document describes the configuration requirements for the YAPPY Engine orchestration layer.

## Consul Connection Requirements

### Engine Startup Requirements

The engine requires Consul to be properly configured before it can start:

1. **Consul Connection Details**
   - Host and port
   - ACL token (if required)
   - Configured via standard Micronaut sources (env vars, `application.yml`, etc.)

2. **No Bootstrap Mode**
   - Engine will fail to start if Consul is not configured
   - No setup mode or self-configuration
   - Configuration must be provided externally

3. **Configuration Sources**
   ```yaml
   consul:
     client:
       host: ${CONSUL_HOST:localhost}
       port: ${CONSUL_PORT:8500}
       acl-token: ${CONSUL_ACL_TOKEN:}
   ```

## Cluster Configuration

### Initial Cluster Setup

Cluster configuration must be created before the engine starts:

1. **Using CLI**
   ```bash
   yappy-cli cluster create \
     --consul-host consul.example.com \
     --cluster-name production
   ```

2. **Using Admin UI**
   - Create cluster configuration
   - Define initial pipelines
   - Configure module mappings

3. **Direct Consul**
   - Create configuration at `/yappy-clusters/<cluster-name>`
   - Must follow PipelineClusterConfig schema

### Engine Startup Process

1. **Read Configuration**
   - Load Consul connection from environment/config files
   - Validate required parameters exist

2. **Connect to Consul**
   - Establish connection using configured parameters
   - Fail fast if connection cannot be established

3. **Load Cluster Configuration**
   - Read PipelineClusterConfig from Consul
   - Validate configuration schema
   - Initialize DynamicConfigurationManager

4. **Start Services**
   - Initialize connector engine
   - Start pipeline engine
   - Begin health monitoring

## Cluster Name Configuration

### No Default Cluster

There is **no default** cluster name in the system. The cluster name must be explicitly configured:

```yaml
yappy:
  cluster:
    name: ${YAPPY_CLUSTER_NAME:}  # Required, no default
```

### Cluster Configuration Structure

The cluster configuration in Consul follows this hierarchy:

```
/yappy-clusters/<cluster-name>/
├── config.json              # PipelineClusterConfig
├── pipelines/
│   ├── document-processing.json
│   └── data-enrichment.json
├── connector-mappings/
│   ├── s3-prod.json
│   └── api-v1.json
└── modules/
    └── registry/
        ├── tika-parser.json
        └── chunker.json
```

## Dynamic Configuration

### Configuration Watch and Update

The engine uses DynamicConfigurationManager to watch for configuration changes:

1. **Automatic Updates**
   - Watches Consul KV for changes
   - Updates pipeline configurations dynamically
   - No restart required for config changes

2. **CAS (Check-And-Set) Support**
   - Prevents concurrent configuration updates
   - Ensures configuration consistency
   - Handles race conditions automatically

3. **Work Distribution**
   - Configuration update tasks distributed via Kafka
   - Ensures only one engine processes each update
   - Prevents duplicate work

## Creating Initial Configuration

### Using CLI Tools

```bash
# Create a new cluster configuration
yappy-cli cluster create \
  --name production \
  --consul-host consul.example.com

# Add a pipeline
yappy-cli pipeline create \
  --cluster production \
  --name document-processing \
  --config pipeline.json

# Register modules
yappy-register register \
  --cluster production \
  --module-endpoint tika:50051 \
  --engine-endpoint engine:50050
```

### Using Admin UI

1. **Pipeline Designer**
   - Visual pipeline creation
   - Drag-and-drop module configuration
   - Automatic validation

2. **Module Registry**
   - View available modules
   - Configure module parameters
   - Test module connectivity

## Normal Operation

Once configured and started, the engine operates continuously:

1. **Message Processing**
   - Receives documents via connector engine
   - Routes through pipeline steps via gRPC
   - Handles errors with retry and DLQ

2. **Health Monitoring**
   - Tracks module health via Consul
   - Updates service status in KV store
   - Exposes health endpoints

3. **Configuration Updates**
   - Watches for pipeline changes
   - Dynamically updates routing
   - No restart required

## Environment Variables

The engine uses standard environment variables for configuration:

```bash
# Required
export CONSUL_HOST=consul.example.com
export CONSUL_PORT=8500
export YAPPY_CLUSTER_NAME=production

# Optional
export CONSUL_ACL_TOKEN=secret-token
export CONSUL_DATACENTER=dc1

# Kafka (when enabled)
export KAFKA_BOOTSTRAP_SERVERS=kafka:9092
```

## Error Handling

### Startup Failures

The engine will fail to start with clear error messages for:

1. **Missing Consul Configuration**
   ```
   ERROR: Consul host not configured. Set CONSUL_HOST environment variable.
   ```

2. **Consul Connection Failed**
   ```
   ERROR: Cannot connect to Consul at consul.example.com:8500
   Check network connectivity and Consul status.
   ```

3. **Missing Cluster Configuration**
   ```
   ERROR: Cluster name not configured. Set YAPPY_CLUSTER_NAME environment variable.
   ```

4. **Invalid Cluster Configuration**
   ```
   ERROR: No configuration found for cluster 'production' in Consul.
   Use CLI or admin tools to create cluster configuration.
   ```

### Resolution Steps

1. **Verify Environment Variables**
   - Check all required variables are set
   - Validate connection parameters

2. **Test Consul Connectivity**
   ```bash
   curl http://consul-host:8500/v1/status/leader
   ```

3. **Create Initial Configuration**
   - Use CLI tools to create cluster
   - Import existing configuration
   - Use admin UI to set up pipelines