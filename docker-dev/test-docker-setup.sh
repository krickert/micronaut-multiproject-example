#!/bin/bash

# Script to test the docker-compose setup

echo "Testing Docker Compose setup in docker-dev directory..."
cd "$(dirname "$0")" || exit 1

# Check if docker-compose is installed
if ! command -v docker-compose &> /dev/null; then
    echo "Error: docker-compose is not installed. Please install it first."
    exit 1
fi

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running. Please start Docker first."
    exit 1
fi

echo "Starting containers with docker-compose..."
docker-compose up -d

# Wait for services to start
echo "Waiting for services to start (30 seconds)..."
sleep 30

# Check if all containers are running
echo "Checking container status..."
CONTAINERS=$(docker-compose ps -q)
ALL_RUNNING=true

for CONTAINER in $CONTAINERS; do
    STATUS=$(docker inspect --format='{{.State.Status}}' "$CONTAINER")
    NAME=$(docker inspect --format='{{.Name}}' "$CONTAINER" | sed 's/^\///')

    echo "Container $NAME status: $STATUS"

    if [ "$STATUS" != "running" ]; then
        ALL_RUNNING=false
        echo "Error: Container $NAME is not running."
        docker logs "$CONTAINER" | tail -n 50
    fi
done

# Test Kafka connection
echo "Testing Kafka connection..."
if docker exec kafka bash -c "if [ -f /opt/kafka/bin/kafka-topics.sh ]; then /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list; else kafka-topics.sh --bootstrap-server kafka:9092 --list; fi" &> /dev/null; then
    echo "Kafka is working correctly."
else
    echo "Error: Could not connect to Kafka."
    ALL_RUNNING=false
fi

# Test Solr connection
echo "Testing Solr connection..."
if curl -s "http://localhost:8983/solr/admin/info/system" | grep -q "solr_home"; then
    echo "Solr is working correctly."
else
    echo "Error: Could not connect to Solr."
    ALL_RUNNING=false
fi

# Test Apicurio connection
echo "Testing Apicurio connection..."
if curl -s "http://localhost:8080/apis" | grep -q "apiVersion"; then
    echo "Apicurio is working correctly."
else
    echo "Error: Could not connect to Apicurio."
    ALL_RUNNING=false
fi

# Test Kafka UI connection
echo "Testing Kafka UI connection..."
if curl -s "http://localhost:8081" | grep -q "html"; then
    echo "Kafka UI is working correctly."
else
    echo "Error: Could not connect to Kafka UI."
    ALL_RUNNING=false
fi

# Test OpenSearch connection
echo "Testing OpenSearch connection..."
if curl -s "http://localhost:9200" | grep -q "version"; then
    echo "OpenSearch is working correctly."
else
    echo "Error: Could not connect to OpenSearch."
    ALL_RUNNING=false
fi

# Test OpenSearch Dashboards connection
echo "Testing OpenSearch Dashboards connection..."
if curl -s "http://localhost:5601" | grep -q "html"; then
    echo "OpenSearch Dashboards is working correctly."
else
    echo "Error: Could not connect to OpenSearch Dashboards."
    ALL_RUNNING=false
fi

# Test Consul connection
echo "Testing Consul connection..."
if curl -s "http://localhost:8500/v1/status/leader" | grep -q ":"; then
    echo "Consul is working correctly."
else
    echo "Error: Could not connect to Consul."
    ALL_RUNNING=false
fi

# Test Moto/Glue Mock connection
echo "Testing Moto/Glue Mock connection..."
if curl -s "http://localhost:5001" | grep -q "moto"; then
    echo "Moto/Glue Mock is working correctly."
else
    echo "Error: Could not connect to Moto/Glue Mock."
    ALL_RUNNING=false
fi

# Summary
if [ "$ALL_RUNNING" = true ]; then
    echo "All containers are running correctly!"
    echo "You can access the services at:"
    echo "- Kafka: localhost:9092"
    echo "- Solr: http://localhost:8983"
    echo "- Apicurio: http://localhost:8080"
    echo "- Kafka UI: http://localhost:8081"
    echo "- OpenSearch: http://localhost:9200"
    echo "- OpenSearch Dashboards: http://localhost:5601"
    echo "- Consul: http://localhost:8500"
    echo "- Moto/Glue Mock: http://localhost:5001"
else
    echo "Some containers are not running correctly. Please check the logs above."
fi

# Ask if user wants to stop containers
read -p "Do you want to stop the containers? (y/n): " STOP_CONTAINERS
if [ "$STOP_CONTAINERS" = "y" ] || [ "$STOP_CONTAINERS" = "Y" ]; then
    echo "Stopping containers..."
    docker-compose down
    echo "Containers stopped."
fi

exit 0
