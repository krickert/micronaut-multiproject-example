# Docker Development Environment

This directory contains a Docker Compose setup for local development with the following services:

1. **Kafka** (in Kraft mode) - Message broker
2. **Apicurio Registry** - Schema registry
3. **Solr** (in cloud mode) - Search platform
4. **Kafka UI** - Web UI for Kafka management

## Prerequisites

- Docker
- Docker Compose

## Getting Started

To start all services, run:

```bash
cd docker-dev
docker-compose up -d
```

To stop all services:

```bash
cd docker-dev
docker-compose down
```

## Testing the Setup

A test script is provided to verify that all services are running correctly:

```bash
cd docker-dev
./test-docker-setup.sh
```

## Service Access

Once the containers are running, you can access the services at:

- **Kafka**: localhost:9092
- **Solr UI**: http://localhost:8983
- **Apicurio Registry**: http://localhost:8080
- **Kafka UI**: http://localhost:8081

## Service Details

### Kafka

- Image: `apache/kafka:latest`
- Port: 9092
- Mode: Kraft (KRaft mode without Zookeeper)
- The Kafka Kraft ID is automatically generated on startup
- Automatically detects and uses Kafka scripts from either /opt/kafka/bin/ or directly from PATH

### Apicurio Registry

- Image: `apicurio/apicurio-registry:latest`
- Port: 8080
- Uses in-memory H2 database

### Solr

- Image: `solr:9.8.1`
- Port: 8983
- Mode: Cloud mode with embedded Zookeeper (started with the -c flag)
- ZK_HOST set to localhost:9983 for internal ZooKeeper connection
- SOLR_HOST set to solr to explicitly define the hostname
- Memory: 3GB
- Default collection: `default_collection`
- Uses -c flag with solr-precreate command to start embedded ZooKeeper

### Kafka UI

- Image: `provectuslabs/kafka-ui:latest`
- Port: 8081
- Depends on Kafka service

## Volumes

The following persistent volumes are created:

- `kafka-data`: Stores Kafka data
- `solr-data`: Stores Solr data

## Troubleshooting

If you encounter issues:

1. Check container logs:
   ```bash
   docker-compose logs [service_name]
   ```

2. Ensure all ports are available and not used by other applications

3. Restart the services:
   ```bash
   docker-compose down
   docker-compose up -d
   ```

4. Run the test script to verify service health:
   ```bash
   ./test-docker-setup.sh
   ```

### Common Issues and Solutions

1. **Kafka Script Path Issues**:
    - If you see errors like `kafka-storage.sh: command not found`, the scripts might be in a different location.
    - Solution: The docker-compose.yml now automatically checks for scripts in both /opt/kafka/bin/ and in the PATH.
    - If issues persist, you may need to modify the paths in the docker-compose.yml file based on your Kafka image.

2. **Solr ZooKeeper Connection Issues**:
    - If Solr fails with `Connection refused` errors when trying to connect to ZooKeeper, check the ZK_HOST setting and ensure the -c flag
      is present.
    - Solution: The current configuration uses the -c flag to start an embedded ZooKeeper server and sets ZK_HOST=localhost:9983.
    - Also sets SOLR_HOST=solr to explicitly define the hostname for better network resolution.
