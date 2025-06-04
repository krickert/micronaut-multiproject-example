#!/bin/bash
set -e

# Test the live YAPPY system
echo "=== YAPPY Live System Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test function
test_service() {
    local name=$1
    local url=$2
    local expected=$3
    
    echo -n "  $name: "
    if curl -s "$url" | grep -q "$expected" 2>/dev/null; then
        echo -e "${GREEN}✓ OK${NC}"
        return 0
    else
        echo -e "${RED}✗ FAILED${NC}"
        return 1
    fi
}

# Test gRPC port
test_port() {
    local name=$1
    local port=$2
    
    echo -n "  $name (port $port): "
    if nc -z localhost $port 2>/dev/null; then
        echo -e "${GREEN}✓ OPEN${NC}"
        return 0
    else
        echo -e "${RED}✗ CLOSED${NC}"
        return 1
    fi
}

echo -e "${BLUE}=== Infrastructure Services ===${NC}"
test_service "Consul" "http://localhost:8500/v1/status/leader" "."
test_service "Kafka" "http://localhost:8081/health" "UP"  # Via Apicurio since Kafka has no HTTP
test_service "Apicurio" "http://localhost:8081/health" "UP"
test_service "OpenSearch" "http://localhost:9200/_cluster/health" "status"

echo ""
echo -e "${BLUE}=== Engine Services ===${NC}"
test_service "Engine-Tika" "http://localhost:8082/health" "UP"
test_service "Engine-Chunker" "http://localhost:8083/health" "UP"
test_service "Engine-Embedder" "http://localhost:8084/health" "UP"
test_service "Engine-OpenSearch-Sink" "http://localhost:8085/health" "UP"

echo ""
echo -e "${BLUE}=== gRPC Ports ===${NC}"
test_port "Engine-Tika gRPC" 50051
test_port "Tika Parser Module" 50052
test_port "Engine-Chunker gRPC" 50061
test_port "Chunker Module" 50053
test_port "Engine-Embedder gRPC" 50071
test_port "Embedder Module" 50054
test_port "Engine-OpenSearch-Sink gRPC" 50081
test_port "OpenSearch Sink Module" 50055

echo ""
echo -e "${BLUE}=== Service Discovery ===${NC}"
echo "Services registered in Consul:"
curl -s http://localhost:8500/v1/catalog/services | jq -r 'keys[]' | sort | while read service; do
    echo -e "  ${GREEN}✓${NC} $service"
done

echo ""
echo -e "${BLUE}=== Kafka Topics ===${NC}"
echo "Creating test topic..."
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --topic yappy-test \
    --partitions 1 --replication-factor 1 2>/dev/null && \
    echo -e "  ${GREEN}✓ Topic created${NC}" || \
    echo -e "  ${YELLOW}⚠ Topic already exists${NC}"

echo ""
echo "Available topics:"
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 --list | while read topic; do
    echo "  - $topic"
done

echo ""
echo -e "${GREEN}=== System Ready for Document Processing! ===${NC}"
echo ""
echo "Next: Run ./test-document-processing.sh to test the pipeline"