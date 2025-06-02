#!/bin/bash

# Start Docker development environment for Yappy

echo "Starting Yappy development environment..."
cd "$(dirname "$0")" || exit 1

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running. Please start Docker first."
    exit 1
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
    
    # Check Consul
    if ! curl -s "http://localhost:8500/v1/status/leader" | grep -q ":"; then
        echo "  Consul not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Consul is ready"
    fi
    
    # Check Kafka
    if ! docker exec kafka bash -c "if [ -f /opt/kafka/bin/kafka-topics.sh ]; then /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list; else kafka-topics.sh --bootstrap-server kafka:9092 --list; fi" &> /dev/null; then
        echo "  Kafka not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ Kafka is ready"
    fi
    
    # Check Apicurio
    if ! curl -s "http://localhost:8080/apis" | grep -q "apiVersion"; then
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
    if ! curl -s "http://localhost:9200" | grep -q "version"; then
        echo "  OpenSearch not ready yet..."
        ALL_READY=false
    else
        echo "  ✓ OpenSearch is ready"
    fi
    
    if [ "$ALL_READY" = true ]; then
        SERVICES_READY=true
    else
        sleep 2
    fi
done

if [ "$SERVICES_READY" = true ]; then
    echo ""
    echo "✓ All services are ready!"
    echo ""
    echo "Service endpoints:"
    echo "  - Consul UI:      http://localhost:8500"
    echo "  - Kafka:          localhost:9094"
    echo "  - Kafka UI:       http://localhost:8081"
    echo "  - Apicurio:       http://localhost:8080"
    echo "  - Moto/Glue:      http://localhost:5001"
    echo "  - OpenSearch:     http://localhost:9200"
    echo "  - OS Dashboards:  http://localhost:5601"
    echo "  - Solr:           http://localhost:8983"
    echo ""
    echo "To stop the environment, run: ./stop-dev-env.sh"
else
    echo ""
    echo "⚠️  Some services failed to start properly."
    echo "Check docker logs with: docker-compose logs [service-name]"
    exit 1
fi