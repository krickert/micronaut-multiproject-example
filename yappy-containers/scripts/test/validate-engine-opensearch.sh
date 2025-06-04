#!/bin/bash
set -e

echo "=== Validating Engine-OpenSearch-Sink Container ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# Clean up
docker stop engine-os-test 2>/dev/null || true
docker rm engine-os-test 2>/dev/null || true

# Start container
echo "Starting engine-opensearch-sink container..."
docker run -d \
    --name engine-os-test \
    --network validate-network \
    -p 28084:8080 \
    -p 60081:50051 \
    -p 60055:50055 \
    -e YAPPY_CLUSTER_NAME=validate-cluster \
    -e CONSUL_HOST=consul-validate \
    -e CONSUL_PORT=8500 \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka-validate:9092 \
    -e OPENSEARCH_HOSTS=opensearch:9200 \
    -e YAPPY_ENGINE_NAME=engine-opensearch-validate \
    nas:5000/yappy/engine-opensearch-sink:latest

echo "Waiting for container to start..."
sleep 10

# Tests
echo ""
echo "=== Running Tests ==="
echo ""

# 1. Container running
echo -n "1. Container is running... "
if docker ps | grep -q engine-os-test; then
    echo -e "${GREEN}✓ PASSED${NC}"
else
    echo -e "${RED}✗ FAILED${NC}"
    docker logs engine-os-test
    exit 1
fi

# 2. Port 50055 for OpenSearch sink
echo -n "2. OpenSearch sink on port 50055... "
if nc -z localhost 60055; then
    echo -e "${GREEN}✓ PASSED${NC}"
else
    echo -e "${RED}✗ FAILED${NC}"
fi

# 3. Both processes running
echo -n "3. Both processes running... "
PROCS=$(docker exec engine-os-test ps aux | grep -E "engine.jar|opensearch-sink.jar" | grep -v grep | wc -l)
if [ $PROCS -eq 2 ]; then
    echo -e "${GREEN}✓ PASSED${NC}"
else
    echo -e "${RED}✗ FAILED${NC}"
    docker exec engine-os-test ps aux
fi

# Cleanup
echo ""
echo "Stopping test container..."
docker stop engine-os-test
docker rm engine-os-test

echo ""
echo -e "${GREEN}Validation complete!${NC}"