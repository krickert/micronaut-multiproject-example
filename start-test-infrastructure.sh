#!/bin/bash
# Script to start and maintain test infrastructure

echo "🚀 Starting test infrastructure..."

# First, clean up any existing containers
echo "🧹 Cleaning up existing containers..."
docker stop $(docker ps -q) 2>/dev/null || true
docker rm $(docker ps -aq) 2>/dev/null || true

# Kill any existing test resources servers
pkill -f "test-resources-service" || true

# Remove .micronaut directories
find . -name ".micronaut" -type d -exec rm -rf {} + 2>/dev/null || true
rm -rf ~/.micronaut/test-resources/

echo "📦 Starting all test containers..."
echo "This will start: Consul, Kafka, Apicurio, OpenSearch, Moto, Engine, etc."

# Run the StartAllTestResourcesTest which forces all containers to start
./gradlew :yappy-orchestrator:engine-integration-test:test --tests '*StartAllTestResourcesTest' --info

echo ""
echo "✅ Test infrastructure started!"
echo ""
echo "You can now run any integration test and the containers will be available."
echo "The test resources server is running in shared mode."
echo ""
echo "To check container status:"
echo "  docker ps"
echo ""
echo "To stop everything:"
echo "  ./cleanup-test-resources.sh"