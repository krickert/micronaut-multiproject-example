#!/bin/bash
set -e

echo "=== Simple Integration Test for Engine-Tika Container ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Test using docker-compose
echo "Starting test environment with docker-compose..."

# Create a simple docker-compose file for testing
cat > docker-compose.integration.yml << EOF
version: '3.8'

services:
  consul:
    image: hashicorp/consul:1.18.0
    environment:
      - CONSUL_BIND_INTERFACE=eth0
      - CONSUL_CLIENT_INTERFACE=eth0
    ports:
      - "8500:8500"
    healthcheck:
      test: ["CMD", "consul", "info"]
      interval: 10s
      timeout: 5s
      retries: 5

  kafka:
    image: apache/kafka:3.7.0
    environment:
      - KAFKA_NODE_ID=1
      - KAFKA_PROCESS_ROLES=broker,controller
      - KAFKA_CONTROLLER_QUORUM_VOTERS=1@kafka:9093
      - KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      - KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092
      - KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
      - KAFKA_LOG_DIRS=/var/lib/kafka/data
      - KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER
      - KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD-SHELL", "kafka-topics.sh --bootstrap-server localhost:9092 --list"]
      interval: 10s
      timeout: 5s
      retries: 5

  engine-tika:
    image: nas:5000/yappy/engine-tika-parser:latest
    environment:
      - YAPPY_CLUSTER_NAME=test-cluster
      - CONSUL_HOST=consul
      - CONSUL_PORT=8500
      - KAFKA_BOOTSTRAP_SERVERS=kafka:9092
      - YAPPY_ENGINE_NAME=engine-tika-test
    ports:
      - "8082:8080"   # Engine HTTP
      - "50051:50051" # Engine gRPC
      - "50052:50052" # Tika module gRPC
    depends_on:
      consul:
        condition: service_healthy
      kafka:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--no-verbose", "--tries=1", "--spider", "http://localhost:8080/health"]
      interval: 10s
      timeout: 5s
      retries: 10
EOF

# Stop any existing services
docker-compose -f docker-compose.integration.yml down -v 2>/dev/null || true

# Start services
docker-compose -f docker-compose.integration.yml up -d

# Wait for services
echo "Waiting for services to be ready..."
sleep 15

# Function to run test
run_test() {
    local test_name=$1
    local test_cmd=$2
    
    echo -n "Test: $test_name... "
    if eval "$test_cmd"; then
        echo -e "${GREEN}PASSED${NC}"
        return 0
    else
        echo -e "${RED}FAILED${NC}"
        return 1
    fi
}

# Run tests
echo ""
echo "=== Running Tests ==="
echo ""

# Test 1: Engine Health
run_test "Engine health check" \
    "curl -s http://localhost:8082/health | grep -q UP"

# Test 2: Consul registration
run_test "Engine registered in Consul" \
    "curl -s http://localhost:8500/v1/health/service/engine-tika-test | grep -q passing"

# Test 3: Check both processes are running
run_test "Both Java processes running" \
    "docker exec \$(docker-compose -f docker-compose.integration.yml ps -q engine-tika) ps aux | grep -E 'java.*engine.jar|java.*tika-parser.jar' | wc -l | grep -q 2"

# Test 4: gRPC ports are open
run_test "Engine gRPC port (50051) open" \
    "nc -z localhost 50051"

run_test "Tika gRPC port (50052) open" \
    "nc -z localhost 50052"

# Test 5: Module is registered
run_test "Tika module registered in Consul" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q tika-parser"

# Test 6: Send a simple gRPC health check
echo ""
echo "Testing gRPC health checks..."
if command -v grpcurl &> /dev/null; then
    run_test "Engine gRPC health" \
        "grpcurl -plaintext localhost:50051 grpc.health.v1.Health/Check"
    
    run_test "Tika gRPC health" \
        "grpcurl -plaintext localhost:50052 grpc.health.v1.Health/Check"
else
    echo -e "${YELLOW}grpcurl not installed, skipping gRPC health checks${NC}"
fi

# Show service status
echo ""
echo "=== Service Status ==="
docker-compose -f docker-compose.integration.yml ps

# Show logs if requested
if [ "$1" == "--logs" ]; then
    echo ""
    echo "=== Engine-Tika Logs ==="
    docker-compose -f docker-compose.integration.yml logs engine-tika | tail -50
fi

# Cleanup
echo ""
echo "To view logs: docker-compose -f docker-compose.integration.yml logs engine-tika"
echo "To stop: docker-compose -f docker-compose.integration.yml down -v"