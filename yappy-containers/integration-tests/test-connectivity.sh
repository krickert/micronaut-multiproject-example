#!/bin/bash
set -e

# Simple connectivity test between containerized engines
echo "=== YAPPY Container Connectivity Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test gRPC connectivity using grpcurl if available
test_grpc_health() {
    local service=$1
    local port=$2
    
    echo -n "  Testing $service gRPC health (port $port)... "
    
    if command -v grpcurl &> /dev/null; then
        if grpcurl -plaintext localhost:$port grpc.health.v1.Health/Check 2>/dev/null | grep -q SERVING; then
            echo -e "${GREEN}✓ SERVING${NC}"
        else
            echo -e "${RED}✗ NOT SERVING${NC}"
        fi
    else
        # Fallback to port check
        if nc -z localhost $port 2>/dev/null; then
            echo -e "${GREEN}✓ Port open${NC}"
        else
            echo -e "${RED}✗ Port closed${NC}"
        fi
    fi
}

# Test HTTP endpoints
test_http_endpoint() {
    local service=$1
    local port=$2
    local endpoint=${3:-/health}
    
    echo -n "  Testing $service HTTP endpoint (port $port)... "
    
    if curl -s "http://localhost:$port$endpoint" | grep -q UP; then
        echo -e "${GREEN}✓ UP${NC}"
    else
        echo -e "${RED}✗ DOWN${NC}"
    fi
}

# Test Consul service discovery
test_consul_services() {
    echo ""
    echo -e "${BLUE}=== Consul Service Discovery ===${NC}"
    
    local services=$(curl -s http://localhost:18500/v1/catalog/services | jq -r 'keys[]' | sort)
    
    echo "Registered services:"
    echo "$services" | while read -r service; do
        if [[ "$service" == *"engine-"* ]] || [[ "$service" == *"parser"* ]] || [[ "$service" == *"chunker"* ]] || [[ "$service" == *"embedder"* ]] || [[ "$service" == *"sink"* ]]; then
            echo -e "  ${GREEN}✓${NC} $service"
        else
            echo "  - $service"
        fi
    done
}

# Test Kafka connectivity
test_kafka() {
    echo ""
    echo -e "${BLUE}=== Kafka Connectivity ===${NC}"
    
    echo -n "  Creating test topic... "
    if docker exec kafka-test kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create --topic test-connectivity \
        --partitions 1 --replication-factor 1 2>/dev/null; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${YELLOW}Already exists${NC}"
    fi
    
    echo -n "  Listing topics... "
    local topics=$(docker exec kafka-test kafka-topics.sh --bootstrap-server localhost:9092 --list 2>/dev/null | wc -l)
    echo -e "${GREEN}✓ Found $topics topics${NC}"
}

# Test inter-service communication
test_interservice() {
    echo ""
    echo -e "${BLUE}=== Inter-Service Communication ===${NC}"
    
    # Test if engines can reach each other through Docker network
    echo -n "  Engine-Tika → Consul... "
    if docker exec engine-tika-test curl -s http://consul:8500/v1/status/leader >/dev/null 2>&1; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
    fi
    
    echo -n "  Engine-Chunker → Kafka... "
    if docker exec engine-chunker-test nc -z kafka 9092 2>/dev/null; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
    fi
    
    echo -n "  Engine-OpenSearch-Sink → OpenSearch... "
    if docker exec engine-opensearch-sink-test curl -s http://opensearch:9200 >/dev/null 2>&1; then
        echo -e "${GREEN}✓${NC}"
    else
        echo -e "${RED}✗${NC}"
    fi
}

# Main execution
main() {
    # Check if test environment is running
    if ! docker ps | grep -q "consul-test"; then
        echo -e "${RED}Error: Test environment not running${NC}"
        echo "Please run: ./run-integration-tests.sh --keep"
        exit 1
    fi
    
    echo -e "${BLUE}=== HTTP Health Checks ===${NC}"
    test_http_endpoint "Engine-Tika" 8082
    test_http_endpoint "Engine-Chunker" 8083
    test_http_endpoint "Engine-Embedder" 8084
    test_http_endpoint "Engine-OpenSearch-Sink" 8085
    
    echo ""
    echo -e "${BLUE}=== gRPC Health Checks ===${NC}"
    test_grpc_health "Engine-Tika" 50051
    test_grpc_health "Tika Parser Module" 50052
    test_grpc_health "Engine-Chunker" 50061
    test_grpc_health "Chunker Module" 50053
    test_grpc_health "Engine-Embedder" 50071
    test_grpc_health "Embedder Module" 50054
    test_grpc_health "Engine-OpenSearch-Sink" 50081
    test_grpc_health "OpenSearch Sink Module" 50055
    
    test_consul_services
    test_kafka
    test_interservice
    
    echo ""
    echo -e "${BLUE}=== Summary ===${NC}"
    echo "All connectivity tests completed."
    echo ""
    echo "To test document processing, run: ./test-document-pipeline.sh"
}

# Install grpcurl if requested
if [ "$1" == "--install-grpcurl" ]; then
    echo "Installing grpcurl..."
    if [[ "$OSTYPE" == "darwin"* ]]; then
        brew install grpcurl
    else
        echo "Please install grpcurl manually from: https://github.com/fullstorydev/grpcurl"
    fi
    exit 0
fi

# Run tests
main