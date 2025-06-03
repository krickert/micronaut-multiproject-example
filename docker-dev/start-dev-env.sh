#!/bin/bash

# Start Docker development environment for Yappy

echo "Starting Yappy development environment..."
cd "$(dirname "$0")" || exit 1

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running. Please start Docker first."
    exit 1
fi

# Load environment variables if .env exists
if [ -f .env ]; then
    echo "Loading environment variables from .env file..."
    export $(cat .env | grep -v '^#' | xargs)
fi

# Stop any existing containers first (in case they're in a bad state)
echo "Stopping any existing containers..."
docker-compose down

# Start containers
echo "Starting Docker containers..."
docker-compose up -d

# Wait for services to be ready
echo "Waiting for services to start..."
sleep 5

# Track service readiness
SERVICES_READY=false
MAX_ATTEMPTS=30
ATTEMPT=0

while [ $ATTEMPT -lt $MAX_ATTEMPTS ] && [ "$SERVICES_READY" = false ]; do
    ATTEMPT=$((ATTEMPT + 1))
    echo "Checking services (attempt $ATTEMPT/$MAX_ATTEMPTS)..."
    
    ALL_READY=true
    
    # Check NAS Registry (optional)
    if [ -n "${NAS_REGISTRY_HOST:-}" ]; then
        if ! curl -s "http://${NAS_REGISTRY_HOST}:5000/v2/" | grep -q "{}"; then
            echo "  NAS Registry (${NAS_REGISTRY_HOST}:5000) not ready yet..."
            # Don't fail if external registry is not ready
        else
            echo "  ✓ NAS Registry (${NAS_REGISTRY_HOST}:5000) is ready"
        fi
    fi
    
    # Check Consul
    if ! curl -s "http://localhost:8500/v1/status/leader" | grep -q ":"; then
        echo "  Consul not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Consul is ready"
    fi
    
    # Check Kafka
    if ! docker exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 &> /dev/null; then
        echo "  Kafka not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Kafka is ready"
    fi
    
    # Check Apicurio
    if ! curl -s "http://localhost:8080/health" | grep -q "UP"; then
        echo "  Apicurio not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Apicurio is ready"
    fi
    
    # Check Moto (Glue mock)
    if ! curl -s "http://localhost:5001" &> /dev/null; then
        echo "  Moto/Glue not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Moto/Glue is ready"
    fi
    
    # Check OpenSearch
    if ! curl -s "http://localhost:9200/_cluster/health" | grep -q "status"; then
        echo "  OpenSearch not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ OpenSearch is ready"
    fi
    
    if [ "$ALL_READY" = true ]; then
        SERVICES_READY=true
    else
        sleep 3
    fi
done

if [ "$SERVICES_READY" = true ]; then
    echo ""
    echo "✓ All services are ready!"
    echo ""
    echo "Service endpoints:"
    echo "  - NAS Registry:     ${NAS_REGISTRY_HOST:-nas}:5000 (if configured)"
    echo "  - Consul UI:       http://localhost:8500"
    echo "  - Kafka:           localhost:9092 (internal), localhost:9094 (external)"
    echo "  - Kafka UI:        http://localhost:8081"
    echo "  - Apicurio:        http://localhost:8080"
    echo "  - Moto/Glue:       http://localhost:5001"
    echo "  - OpenSearch:      http://localhost:9200"
    echo "  - OS Dashboards:   http://localhost:5601"
    echo ""
    echo "Network: yappy-network"
    echo ""
    echo "To stop the environment, run: ./stop-dev-env.sh"
    echo "To check service status, run: ./status-dev-env.sh"
else
    echo ""
    echo "⚠️  Some services failed to start properly."
    echo "Check docker logs with: docker-compose logs [service-name]"
    exit 1
fi