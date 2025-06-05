#!/bin/bash
set -e

echo "Building Tika Parser container..."

# Navigate to root project directory
cd ../..

# Build the required JARs
echo "Building yappy-engine JAR..."
./gradlew :yappy-engine:shadowJar

echo "Building tika-parser module JAR..."
./gradlew :yappy-modules:tika-parser:shadowJar

# Build the Docker image
echo "Building Docker image..."
cd yappy-containers/engine-tika-parser

# Build using docker directly (since we don't have the gradle plugin set up in the main build)
docker build -t yappy/engine-tika-parser:latest -f Dockerfile ../..

echo "Starting test environment..."
docker-compose -f docker-compose.test.yml up -d

echo "Waiting for services to start..."
sleep 30

echo "Checking service health..."
# Check Consul
curl -s http://localhost:8500/v1/status/leader || echo "Consul not ready"

# Check Engine health
curl -s http://localhost:8080/health || echo "Engine not ready"

echo ""
echo "Services started. You can:"
echo "  - View Consul UI at http://localhost:8500"
echo "  - Check engine health at http://localhost:8080/health"
echo "  - View logs with: docker-compose -f docker-compose.test.yml logs -f"
echo "  - Stop services with: docker-compose -f docker-compose.test.yml down"
echo ""
echo "To test Tika Parser, you can send a gRPC request to localhost:50053"