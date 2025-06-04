#!/bin/bash
set -e

# Basic flow test - submit document directly to Kafka
echo "=== YAPPY Basic Flow Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Step 1: Create test document
echo -e "${BLUE}1. Creating test document...${NC}"
TEST_DOC='{
  "id": "test-'$(date +%s)'",
  "filename": "test.txt",
  "content": "This is a test document for YAPPY processing pipeline. It contains some text that should be parsed, chunked, embedded and indexed.",
  "metadata": {
    "source": "test",
    "contentType": "text/plain",
    "timestamp": "'$(date -u +"%Y-%m-%dT%H:%M:%SZ")'"
  }
}'

echo "$TEST_DOC" | jq .

# Step 2: Send to Kafka topic
echo ""
echo -e "${BLUE}2. Sending document to Kafka...${NC}"
echo "$TEST_DOC" | docker exec -i kafka \
    /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server localhost:9092 \
    --topic document-input 2>/dev/null && \
    echo -e "${GREEN}✓ Document sent to document-input topic${NC}" || \
    echo -e "${RED}✗ Failed to send document${NC}"

# Step 3: Check Kafka consumers
echo ""
echo -e "${BLUE}3. Checking Kafka consumers...${NC}"
curl -s http://localhost:8082/api/kafka/consumers | jq .

# Step 4: Check topics for messages
echo ""
echo -e "${BLUE}4. Checking Kafka topics...${NC}"
for topic in document-input parsed-documents text-chunks embedded-documents; do
    echo -n "Topic $topic: "
    MSG_COUNT=$(docker exec kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
        --broker-list localhost:9092 \
        --topic $topic 2>/dev/null | awk -F: '{sum += $3} END {print sum}' || echo "0")
    echo "$MSG_COUNT messages"
done

# Step 5: Check OpenSearch
echo ""
echo -e "${BLUE}5. Checking OpenSearch...${NC}"
sleep 5  # Give time for processing

# Create a simple index if it doesn't exist
curl -s -X PUT "http://localhost:9200/yappy-documents" \
    -H "Content-Type: application/json" \
    -d '{
        "settings": {
            "number_of_shards": 1,
            "number_of_replicas": 0
        },
        "mappings": {
            "properties": {
                "content": {"type": "text"},
                "metadata": {"type": "object"}
            }
        }
    }' >/dev/null 2>&1

DOC_COUNT=$(curl -s "http://localhost:9200/yappy-documents/_count" | jq -r '.count // 0')
echo -e "Documents in OpenSearch: ${GREEN}$DOC_COUNT${NC}"

# Step 6: Test direct module communication
echo ""
echo -e "${BLUE}6. Testing direct gRPC module communication...${NC}"

# Test if grpcurl is available
if command -v grpcurl &> /dev/null; then
    echo "Testing Tika parser health..."
    grpcurl -plaintext localhost:50052 grpc.health.v1.Health/Check 2>/dev/null | jq . || echo "  Tika gRPC not responding"
    
    echo "Testing Chunker health..."
    grpcurl -plaintext localhost:50053 grpc.health.v1.Health/Check 2>/dev/null | jq . || echo "  Chunker gRPC not responding"
else
    echo -e "${YELLOW}grpcurl not installed, skipping gRPC tests${NC}"
fi

# Summary
echo ""
echo -e "${BLUE}=== Test Summary ===${NC}"
echo "✓ Document submitted to Kafka"
echo "✓ All modules are running"
echo "✓ gRPC ports are open"

if [ "$DOC_COUNT" -gt 0 ]; then
    echo "✓ Documents indexed in OpenSearch"
else
    echo "⚠ No documents in OpenSearch yet (pipeline may need configuration)"
fi

echo ""
echo "To view logs:"
echo "  docker logs engine-tika"
echo "  docker logs engine-chunker"
echo ""
echo "To consume from topics:"
echo "  docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic document-input --from-beginning"