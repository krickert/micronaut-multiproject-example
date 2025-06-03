# Yappy Development Environment

This directory contains the Docker Compose configuration for the Yappy development environment.

## Services

The development environment includes the following services:

1. **Kafka** (ports 9092, 9094) - Event streaming platform in KRaft mode
2. **Apicurio Registry** (port 8080) - Schema registry for Kafka
3. **Consul** (port 8500) - Service discovery and configuration
4. **OpenSearch** (port 9200) - Search and analytics engine
5. **OpenSearch Dashboards** (port 5601) - Visualization for OpenSearch
6. **Moto/Glue** (port 5001) - AWS Glue mock for testing
7. **Kafka UI** (port 8081) - Web UI for Kafka management

**Note:** Docker Registry is now external (default: nas:5000). Configure with NAS_REGISTRY_HOST environment variable.

## Quick Start

1. **Start all services:**
   ```bash
   ./start-dev-env.sh
   ```

2. **Check service status:**
   ```bash
   ./status-dev-env.sh
   ```

3. **Stop all services:**
   ```bash
   ./stop-dev-env.sh
   ```

## Configuration

### Environment Variables

Copy `.env.example` to `.env` and adjust values as needed:

```bash
cp .env.example .env
```

### Service URLs

- **External Registry**: http://nas:5000 (or configure NAS_REGISTRY_HOST)
- **Consul UI**: http://localhost:8500
- **Kafka**: localhost:9092 (internal), localhost:9094 (external)
- **Kafka UI**: http://localhost:8081
- **Apicurio Registry**: http://localhost:8080
- **OpenSearch**: http://localhost:9200
- **OpenSearch Dashboards**: http://localhost:5601
- **Moto/Glue**: http://localhost:5001

### Network

All services are connected via the `yappy-network` bridge network.

### Volumes

Data is persisted in Docker volumes:
- `registry-data` - Docker registry data
- `kafka-data` - Kafka logs and data
- `opensearch-data` - OpenSearch indices
- `consul-data` - Consul data

## Health Checks

All services include health checks to ensure they're properly initialized before dependent services start.

## Service Details

### Docker Registry
- Image: `registry:2`
- Port: 5000
- Provides local container registry for development

### Kafka
- Image: `apache/kafka:3.7.0`
- Ports: 9092 (internal), 9094 (external)
- Mode: KRaft (without Zookeeper)
- Memory: 4GB heap
- Cluster ID: yappy-dev

### Apicurio Registry
- Image: `apicurio/apicurio-registry:2.5.11.Final`
- Port: 8080
- Uses in-memory H2 database
- CORS enabled for development

### Consul
- Image: `hashicorp/consul:1.18.0`
- Port: 8500 (UI/API), 8600 (DNS), 8300-8301 (cluster)
- Mode: Development mode with UI enabled

### OpenSearch
- Image: `opensearchproject/opensearch:3.0.0`
- Port: 9200 (API), 9600 (Performance Analyzer)
- Mode: Single-node
- Memory: 1GB heap
- Security disabled for development

### OpenSearch Dashboards
- Image: `opensearchproject/opensearch-dashboards:3.0.0`
- Port: 5601
- Security disabled for development

### Moto/Glue
- Image: `motoserver/moto:5.0.2`
- Port: 5001
- Provides AWS Glue mock for testing

### Kafka UI
- Image: `provectuslabs/kafka-ui:latest`
- Port: 8081
- Connected to Kafka and Apicurio Registry

## Troubleshooting

1. **View logs for a specific service:**
   ```bash
   docker-compose logs -f [service-name]
   ```

2. **Restart a specific service:**
   ```bash
   docker-compose restart [service-name]
   ```

3. **Clean up everything (including volumes):**
   ```bash
   docker-compose down -v
   ```

4. **Check service health:**
   ```bash
   ./status-dev-env.sh
   ```

## Updates from Previous Version

- **Removed**: Solr service (replaced by OpenSearch)
- **Added**: Local Docker registry for development
- **Added**: Consul for service discovery
- **Updated**: All services to latest stable versions
- **Added**: Comprehensive health checks for all services
- **Improved**: Network configuration with named network
- **Added**: Volume persistence for all stateful services
- **Added**: Status checking script
- **Enhanced**: Start script with health checks