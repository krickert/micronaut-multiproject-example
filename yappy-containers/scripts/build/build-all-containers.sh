#!/bin/bash
set -e

echo "=== Building All YAPPY Module Containers ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# Track build status
FAILED_BUILDS=""

# Function to build a container
build_container() {
    local name=$1
    local script=$2
    
    echo -e "${YELLOW}Building $name...${NC}"
    if ./$script; then
        echo -e "${GREEN}✓ $name built successfully${NC}"
        echo ""
    else
        echo -e "${RED}✗ $name build failed${NC}"
        FAILED_BUILDS="$FAILED_BUILDS $name"
        echo ""
    fi
}

# First build all modules
echo "=== Building Module JARs ==="
echo "This ensures all modules are up to date..."
(cd .. && ./gradlew shadowJar)

echo ""
echo "=== Building Container Images ==="
echo ""

# Build each container
build_container "engine-tika-parser" "build-engine-tika.sh"
build_container "engine-chunker" "build-engine-chunker.sh"
build_container "engine-embedder" "build-engine-embedder.sh"
build_container "engine-opensearch-sink" "build-engine-opensearch-sink.sh"
build_container "engine-test-connector" "build-engine-test-connector.sh"

# Summary
echo ""
echo "=== Build Summary ==="
echo ""

# List built images
echo "Successfully built images:"
docker images | grep "yappy/engine-" | grep -v "<none>" | awk '{print "  - " $1 ":" $2}' | sort -u

# Report failures
if [ -n "$FAILED_BUILDS" ]; then
    echo ""
    echo -e "${RED}Failed builds:${NC}"
    echo "$FAILED_BUILDS" | tr ' ' '\n' | grep -v "^$" | awk '{print "  - " $0}'
    exit 1
else
    echo ""
    echo -e "${GREEN}All containers built successfully!${NC}"
fi

echo ""
echo "=== Port Mapping Reference ==="
echo "Engine HTTP: 8080"
echo "Engine gRPC: 50051"
echo "Module gRPC ports:"
echo "  - Tika Parser:      50052"
echo "  - Chunker:          50053"
echo "  - Embedder:         50054"
echo "  - OpenSearch Sink:  50055"
echo "  - Test Connector:   50059"