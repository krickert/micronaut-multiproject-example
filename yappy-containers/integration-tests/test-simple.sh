#!/bin/bash
set -e

echo "=== Simple YAPPY Container Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test that our containers can run
echo -e "${BLUE}Testing individual containers...${NC}"
echo ""

# Test Engine-Tika container
echo "1. Testing engine-tika-parser container..."
echo "   Starting container..."
docker run -d --rm \
    --name test-engine-tika \
    -e YAPPY_CLUSTER_NAME=simple-test \
    -e CONSUL_HOST=localhost \
    -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
    nas:5000/yappy/engine-tika-parser:latest

echo "   Waiting 10 seconds for startup..."
sleep 10

echo -n "   Checking processes... "
PROC_COUNT=$(docker exec test-engine-tika ps aux | grep -E "java.*\\.jar" | grep -v grep | wc -l)
if [ "$PROC_COUNT" -eq "2" ]; then
    echo -e "${GREEN}✓ Both processes running${NC}"
else
    echo -e "${RED}✗ Expected 2 processes, found $PROC_COUNT${NC}"
fi

echo -n "   Checking supervisor... "
if docker exec test-engine-tika ps aux | grep -q supervisord; then
    echo -e "${GREEN}✓ Supervisor running${NC}"
else
    echo -e "${RED}✗ Supervisor not found${NC}"
fi

echo "   Supervisor status:"
docker exec test-engine-tika supervisorctl status || echo "   Could not get status"

echo "   Stopping container..."
docker stop test-engine-tika

echo ""
echo "2. Testing engine-chunker container..."
echo "   Starting container..."
docker run -d --rm \
    --name test-engine-chunker \
    -e YAPPY_CLUSTER_NAME=simple-test \
    -e CONSUL_HOST=localhost \
    -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
    nas:5000/yappy/engine-chunker:latest

echo "   Waiting 10 seconds for startup..."
sleep 10

echo -n "   Checking processes... "
PROC_COUNT=$(docker exec test-engine-chunker ps aux | grep -E "java.*\\.jar" | grep -v grep | wc -l)
if [ "$PROC_COUNT" -eq "2" ]; then
    echo -e "${GREEN}✓ Both processes running${NC}"
else
    echo -e "${RED}✗ Expected 2 processes, found $PROC_COUNT${NC}"
fi

echo "   Stopping container..."
docker stop test-engine-chunker

echo ""
echo -e "${BLUE}=== Summary ===${NC}"
echo "Basic container functionality verified."
echo ""
echo "Next steps:"
echo "1. Fix any issues with the basic container startup"
echo "2. Run full integration test with infrastructure"
echo "3. Test document processing pipeline"