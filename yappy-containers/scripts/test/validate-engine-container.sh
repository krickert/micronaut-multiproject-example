#!/bin/bash
set -e

echo "=== Validating Engine-Tika Container ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m'

# Clean up any existing test containers
echo "Cleaning up existing containers..."
docker stop engine-tika-validate consul-validate kafka-validate 2>/dev/null || true
docker rm engine-tika-validate consul-validate kafka-validate 2>/dev/null || true
docker network rm validate-network 2>/dev/null || true

# Create test network
echo "Creating test network..."
docker network create validate-network

# Start minimal infrastructure
echo "Starting Consul..."
docker run -d \
    --name consul-validate \
    --network validate-network \
    -p 18500:8500 \
    -e CONSUL_BIND_INTERFACE=eth0 \
    -e CONSUL_CLIENT_INTERFACE=eth0 \
    hashicorp/consul:1.18.0

echo "Starting Kafka (KRaft mode)..."
docker run -d \
    --name kafka-validate \
    --network validate-network \
    -p 19092:9092 \
    -e KAFKA_NODE_ID=1 \
    -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka-validate:9093 \
    -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka-validate:9092 \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_LOG_DIRS=/var/lib/kafka/data \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=true \
    apache/kafka:3.7.0

echo "Waiting for infrastructure..."
sleep 10

# Verify Kafka is running in KRaft mode
echo -n "Verifying Kafka KRaft mode... "
if docker exec kafka-validate ls /var/lib/kafka/data | grep -q "__cluster_metadata"; then
    echo -e "${GREEN}YES - KRaft mode confirmed${NC}"
else
    echo -e "${RED}NO - Not in KRaft mode${NC}"
fi

# Start engine-tika container
echo "Starting engine-tika container..."
docker run -d \
    --name engine-tika-validate \
    --network validate-network \
    -p 18082:8080 \
    -p 60051:50051 \
    -p 60052:50052 \
    -e YAPPY_CLUSTER_NAME=validate-cluster \
    -e CONSUL_HOST=consul-validate \
    -e CONSUL_PORT=8500 \
    -e KAFKA_BOOTSTRAP_SERVERS=kafka-validate:9092 \
    -e YAPPY_ENGINE_NAME=engine-tika-validate \
    nas:5000/yappy/engine-tika-parser:latest

echo "Waiting for container to start..."
sleep 15

# Validation tests
echo ""
echo "=== Running Validation Tests ==="
echo ""

# Test function
test_result() {
    if [ $1 -eq 0 ]; then
        echo -e "${GREEN}✓ PASSED${NC}"
    else
        echo -e "${RED}✗ FAILED${NC}"
        FAILED=true
    fi
}

# 1. Container running
echo -n "1. Container is running... "
docker ps | grep -q engine-tika-validate
test_result $?

# 2. Both processes running
echo -n "2. Both Java processes running... "
PROC_COUNT=$(docker exec engine-tika-validate ps aux | grep -E "java.*\.jar" | grep -v grep | wc -l)
[ $PROC_COUNT -eq 2 ]
test_result $?

# 3. Engine health
echo -n "3. Engine health endpoint... "
curl -s http://localhost:18082/health | grep -q "UP"
test_result $?

# 4. Consul registration
echo -n "4. Engine registered in Consul... "
curl -s http://localhost:18500/v1/catalog/services | grep -q "engine-tika-validate"
test_result $?

# 5. Module registered
echo -n "5. Tika module registered... "
curl -s http://localhost:18500/v1/catalog/services | grep -q "tika-parser"
test_result $?

# 6. Port separation (50051 vs 50052)
echo -n "6. Port separation working... "
nc -z localhost 60051 && nc -z localhost 60052
test_result $?

# 7. Supervisor managing processes
echo -n "7. Supervisor is running... "
docker exec engine-tika-validate ps aux | grep -q supervisord
test_result $?

# Show service details
echo ""
echo "=== Service Details ==="
echo "Consul services:"
curl -s http://localhost:18500/v1/catalog/services | jq .

echo ""
echo "Module health info:"
curl -s http://localhost:18500/v1/health/service/tika-parser | jq '.[0].Service | {ID, Service, Port, Tags}'

# Cleanup instructions
echo ""
echo "=== Cleanup ==="
echo "To view logs: docker logs engine-tika-validate"
echo "To stop: docker stop engine-tika-validate consul-validate kafka-validate"
echo "To remove: docker rm engine-tika-validate consul-validate kafka-validate"
echo "          docker network rm validate-network"

# Exit with error if any test failed
if [ "$FAILED" = true ]; then
    exit 1
fi

echo ""
echo -e "${GREEN}All validation tests passed!${NC}"