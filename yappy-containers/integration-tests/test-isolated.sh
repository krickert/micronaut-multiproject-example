#!/bin/bash
set -e

# Isolated integration test - no port conflicts
echo "=== YAPPY Isolated Container Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test configuration
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$TEST_DIR/docker-compose.isolated.yml"

# Cleanup function
cleanup() {
    echo ""
    echo "Cleaning up test environment..."
    docker-compose -f "$COMPOSE_FILE" down -v --remove-orphans 2>/dev/null || true
}

# Set trap for cleanup on exit
trap cleanup EXIT

# Function to run a test inside containers
run_container_test() {
    local test_name=$1
    local container=$2
    local test_cmd=$3
    
    echo -n "  $test_name... "
    if docker exec "$container" sh -c "$test_cmd" >/dev/null 2>&1; then
        echo -e "${GREEN}✓ PASSED${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        return 1
    fi
}

# Start test environment
echo "Starting isolated test environment..."
docker-compose -f "$COMPOSE_FILE" up -d

# Wait for services to stabilize
echo "Waiting for services to initialize (30s)..."
sleep 30

# Run tests through container exec commands
echo ""
echo -e "${BLUE}=== Container Health Tests ===${NC}"

# Check if containers are running
for container in consul-test-isolated kafka-test-isolated apicurio-test-isolated opensearch-test-isolated \
                 engine-tika-test-isolated engine-chunker-test-isolated \
                 engine-embedder-test-isolated engine-opensearch-sink-test-isolated; do
    echo -n "  Container $container running... "
    if docker ps | grep -q "$container"; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
    fi
done

# Infrastructure connectivity tests
echo ""
echo -e "${BLUE}=== Infrastructure Connectivity ===${NC}"

run_container_test "Consul accessible from Engine-Tika" \
    "engine-tika-test-isolated" \
    "curl -s http://consul-isolated:8500/v1/status/leader"

run_container_test "Kafka accessible from Engine-Chunker" \
    "engine-chunker-test-isolated" \
    "nc -z kafka-isolated 9092"

run_container_test "Apicurio accessible from Engine-Embedder" \
    "engine-embedder-test-isolated" \
    "curl -s http://apicurio-isolated:8080/health"

run_container_test "OpenSearch accessible from Sink" \
    "engine-opensearch-sink-test-isolated" \
    "curl -s http://opensearch-isolated:9200"

# Service registration tests
echo ""
echo -e "${BLUE}=== Service Registration ===${NC}"

run_container_test "Services registered in Consul" \
    "consul-test-isolated" \
    "consul catalog services | grep -E 'engine|parser|chunker|embedder|sink'"

# Process verification
echo ""
echo -e "${BLUE}=== Process Verification ===${NC}"

run_container_test "Engine-Tika dual processes" \
    "engine-tika-test-isolated" \
    "ps aux | grep -E 'java.*\\.jar' | grep -v grep | wc -l | grep -q 2"

run_container_test "Engine-Chunker dual processes" \
    "engine-chunker-test-isolated" \
    "ps aux | grep -E 'java.*\\.jar' | grep -v grep | wc -l | grep -q 2"

run_container_test "Engine-Embedder dual processes" \
    "engine-embedder-test-isolated" \
    "ps aux | grep -E 'java.*\\.jar' | grep -v grep | wc -l | grep -q 2"

run_container_test "Engine-OpenSearch-Sink dual processes" \
    "engine-opensearch-sink-test-isolated" \
    "ps aux | grep -E 'java.*\\.jar' | grep -v grep | wc -l | grep -q 2"

# Inter-service communication test
echo ""
echo -e "${BLUE}=== Inter-Service Communication ===${NC}"

# Create a test topic
echo -n "  Creating test topic in Kafka... "
if docker exec kafka-test-isolated kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --topic integration-test \
    --partitions 1 --replication-factor 1 2>/dev/null; then
    echo -e "${GREEN}✓${NC}"
else
    echo -e "${YELLOW}Already exists${NC}"
fi

# Show summary
echo ""
echo -e "${BLUE}=== Test Summary ===${NC}"
echo "Container status:"
docker-compose -f "$COMPOSE_FILE" ps --format "table {{.Name}}\t{{.Status}}\t{{.State}}"

# Show registered services
echo ""
echo "Services in Consul:"
docker exec consul-test-isolated consul catalog services 2>/dev/null | sort || echo "  Unable to list services"

# Option to keep running
if [ "$1" == "--keep" ]; then
    echo ""
    echo -e "${YELLOW}Test environment kept running${NC}"
    echo "To view logs: docker-compose -f $COMPOSE_FILE logs -f [service]"
    echo "To exec into container: docker exec -it [container-name] sh"
    echo "To stop: docker-compose -f $COMPOSE_FILE down -v"
    trap - EXIT  # Remove cleanup trap
else
    echo ""
    echo "To run tests and keep environment: $0 --keep"
fi