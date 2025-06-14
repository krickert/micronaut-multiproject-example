#!/bin/bash

# Complete startup script for YAPPY development environment

echo "🚀 Starting complete YAPPY development environment..."
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Function to check if a command was successful
check_status() {
    if [ $? -eq 0 ]; then
        echo -e "${GREEN}✓ $1${NC}"
    else
        echo -e "${RED}✗ $1 failed${NC}"
        exit 1
    fi
}

# Step 1: Start infrastructure
echo -e "${YELLOW}Step 1: Starting infrastructure services...${NC}"
./start-dev-env.sh
check_status "Infrastructure started"
echo ""

# Step 2: Verify services
echo -e "${YELLOW}Step 2: Verifying services...${NC}"
./test-dev-env.sh
check_status "Services verified"
echo ""

# Step 3: Setup cluster configuration
echo -e "${YELLOW}Step 3: Setting up YAPPY cluster...${NC}"
./setup-yappy-cluster.sh
check_status "Cluster configured"
echo ""

# Step 4: Start the engine
echo -e "${YELLOW}Step 4: Starting YAPPY engine...${NC}"
echo "Starting engine in background (with debug on port 5000)..."

# Start engine in background with logs
nohup ./run-engine-local.sh --debug > engine.log 2>&1 &
ENGINE_PID=$!
echo "Engine started with PID: $ENGINE_PID"
echo "Logs available at: engine.log"
echo "Debug port: 5000"

# Wait for engine to be ready
echo "Waiting for engine to be ready..."
sleep 10

# Check if engine is running
if ps -p $ENGINE_PID > /dev/null; then
    echo -e "${GREEN}✓ Engine is running${NC}"
else
    echo -e "${RED}✗ Engine failed to start${NC}"
    echo "Check engine.log for details"
    exit 1
fi

# Step 5: Test engine health
echo -e "${YELLOW}Step 5: Testing engine health...${NC}"
if curl -s http://localhost:8090/health | grep -q 'UP'; then
    echo -e "${GREEN}✓ Engine health check passed${NC}"
else
    echo -e "${YELLOW}⚠ Engine health check failed (may still be starting)${NC}"
fi

echo ""
echo -e "${GREEN}🎉 YAPPY development environment is ready!${NC}"
echo ""
echo "📊 Service Status:"
echo "  - Consul UI: http://localhost:8500"
echo "  - Kafka: localhost:9092"
echo "  - Apicurio: http://localhost:8080"
echo "  - Engine HTTP: http://localhost:8090"
echo "  - Engine gRPC: localhost:50070"
echo "  - Engine Debug: localhost:5000"
echo ""
echo "📝 Available Commands:"
echo "  - Send test document: ./send-test-document.sh"
echo "  - Monitor Kafka: ./monitor-kafka.sh"
echo "  - View engine logs: tail -f engine.log"
echo "  - Stop engine: kill $ENGINE_PID"
echo ""
echo "🔧 Debug Ports:"
echo "  - Engine: 5000"
echo "  - Tika: 5005"
echo "  - Chunker: 5006"
echo "  - Embedder: 5007"
echo "  - Echo: 5008"
echo "  - Test Module: 5009"
echo ""
echo "💡 Next steps:"
echo "  1. Attach debugger to any service"
echo "  2. Send test documents with ./send-test-document.sh"
echo "  3. Monitor output with ./monitor-kafka.sh"

# Save PID for later
echo $ENGINE_PID > .engine.pid
echo ""
echo "Engine PID saved to .engine.pid"