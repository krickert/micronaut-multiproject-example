#!/bin/bash
# Script to clean up and restart test resources

echo "🧹 Cleaning up test resources..."

# Stop gradle daemon
echo "📛 Stopping Gradle daemon..."
./gradlew --stop

# Remove .micronaut directories
echo "🗑️  Removing .micronaut directories..."
find . -name ".micronaut" -type d -exec rm -rf {} + 2>/dev/null || true

# Stop all test containers
echo "🐳 Stopping test containers..."
docker ps -a | grep -E "testcontainers|consul|kafka|apicurio|engine|chunker|test-module" | awk '{print $1}' | xargs -r docker stop
docker ps -a | grep -E "testcontainers|consul|kafka|apicurio|engine|chunker|test-module" | awk '{print $1}' | xargs -r docker rm

# Kill any lingering test resources processes
echo "💀 Killing test resources processes..."
pkill -f "test-resources-service" || true

echo "✅ Cleanup complete!"
echo ""
echo "To restart test resources, run:"
echo "./gradlew :yappy-orchestrator:engine-integration-test:test --tests '*TestResourcesInitializationTest' --info"