#!/bin/bash
set -e

# Integration test runner for containerized YAPPY engines
echo "=== YAPPY Container Integration Tests ==="
echo ""

# Colors for output
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Test configuration
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$TEST_DIR/docker-compose.test.yml"
TEST_TIMEOUT=300  # 5 minutes total timeout

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up test environment..."
    docker-compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
}

# Set trap for cleanup on exit
trap cleanup EXIT

# Function to wait for service
wait_for_service() {
    local service=$1
    local url=$2
    local max_attempts=30
    local attempt=1
    
    echo -n "Waiting for $service..."
    while [ $attempt -le $max_attempts ]; do
        if curl -s "$url" >/dev/null 2>&1; then
            echo -e " ${GREEN}✓${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        ((attempt++))
    done
    echo -e " ${RED}✗ Timeout${NC}"
    return 1
}

# Function to run a test
run_test() {
    local test_name=$1
    local test_cmd=$2
    
    echo -n "  $test_name... "
    if eval "$test_cmd" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ PASSED${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        return 1
    fi
}

# Start test environment
echo "Starting test environment..."
docker-compose -f "$COMPOSE_FILE" up -d

# Wait for infrastructure services
echo ""
echo "Waiting for infrastructure services..."
wait_for_service "Consul" "http://localhost:8500/v1/status/leader"
wait_for_service "Kafka" "http://localhost:9092" || echo "  (Kafka doesn't have HTTP, checking via docker)"
wait_for_service "Apicurio" "http://localhost:8081/health"
wait_for_service "OpenSearch" "http://localhost:9200"

# Extra wait for Kafka
echo -n "Verifying Kafka is ready..."
if docker exec kafka-test kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
    echo -e " ${GREEN}✓${NC}"
else
    echo -e " ${RED}✗${NC}"
    exit 1
fi

# Wait for engine services
echo ""
echo "Waiting for engine services..."
wait_for_service "Engine-Tika" "http://localhost:8082/health"
wait_for_service "Engine-Chunker" "http://localhost:8083/health"
wait_for_service "Engine-Embedder" "http://localhost:8084/health"
wait_for_service "Engine-OpenSearch-Sink" "http://localhost:8085/health"

# Run infrastructure tests
echo ""
echo -e "${BLUE}=== Infrastructure Tests ===${NC}"
run_test "Consul cluster health" \
    "curl -s http://localhost:8500/v1/status/leader | grep -q '\"'"

run_test "Kafka broker available" \
    "docker exec kafka-test kafka-topics.sh --bootstrap-server localhost:9092 --list"

run_test "Apicurio registry ready" \
    "curl -s http://localhost:8081/health/ready | grep -q UP"

run_test "OpenSearch cluster health" \
    "curl -s http://localhost:9200/_cluster/health | grep -q '\"status\":\"green\\|yellow\"'"

# Run service registration tests
echo ""
echo -e "${BLUE}=== Service Registration Tests ===${NC}"
run_test "Engine-Tika registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q engine-tika-test"

run_test "Engine-Chunker registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q engine-chunker-test"

run_test "Engine-Embedder registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q engine-embedder-test"

run_test "Engine-OpenSearch-Sink registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q engine-opensearch-sink-test"

# Run module registration tests
echo ""
echo -e "${BLUE}=== Module Registration Tests ===${NC}"
run_test "Tika module registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q tika-parser"

run_test "Chunker module registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q chunker"

run_test "Embedder module registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q embedder"

run_test "OpenSearch-Sink module registered" \
    "curl -s http://localhost:8500/v1/catalog/services | grep -q opensearch-sink"

# Run process verification tests
echo ""
echo -e "${BLUE}=== Process Verification Tests ===${NC}"
run_test "Engine-Tika processes running" \
    "docker exec engine-tika-test ps aux | grep -E 'java.*engine.jar|java.*tika-parser.jar' | wc -l | grep -q 2"

run_test "Engine-Chunker processes running" \
    "docker exec engine-chunker-test ps aux | grep -E 'java.*engine.jar|java.*chunker.jar' | wc -l | grep -q 2"

run_test "Engine-Embedder processes running" \
    "docker exec engine-embedder-test ps aux | grep -E 'java.*engine.jar|java.*embedder.jar' | wc -l | grep -q 2"

run_test "Engine-OpenSearch-Sink processes running" \
    "docker exec engine-opensearch-sink-test ps aux | grep -E 'java.*engine.jar|java.*opensearch-sink.jar' | wc -l | grep -q 2"

# Run port availability tests
echo ""
echo -e "${BLUE}=== Port Availability Tests ===${NC}"
run_test "Tika gRPC port (50052)" "nc -z localhost 50052"
run_test "Chunker gRPC port (50053)" "nc -z localhost 50053"
run_test "Embedder gRPC port (50054)" "nc -z localhost 50054"
run_test "OpenSearch-Sink gRPC port (50055)" "nc -z localhost 50055"

# Show service summary
echo ""
echo -e "${BLUE}=== Service Summary ===${NC}"
echo "Registered services in Consul:"
curl -s http://localhost:8500/v1/catalog/services | jq 'keys[]' | sort

echo ""
echo -e "${BLUE}=== Container Status ===${NC}"
docker-compose -f "$COMPOSE_FILE" ps

# Option to keep environment running
if [ "$1" == "--keep" ]; then
    echo ""
    echo -e "${YELLOW}Test environment kept running for manual testing${NC}"
    echo "To view logs: docker-compose -f $COMPOSE_FILE logs -f [service-name]"
    echo "To stop: docker-compose -f $COMPOSE_FILE down -v"
    trap - EXIT  # Remove cleanup trap
else
    echo ""
    echo "To run tests and keep environment: $0 --keep"
fi