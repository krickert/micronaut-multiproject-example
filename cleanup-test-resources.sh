#!/bin/bash
# Script to clean up and restart test resources

echo "🧹 Cleaning up test resources..."

# Stop gradle daemon
echo "📛 Stopping Gradle daemon..."
./gradlew --stop

# Kill test resources processes FIRST before removing directories
echo "💀 Killing test resources processes..."
pkill -f "test-resources-service" || true
pkill -f "TestResourcesService" || true
# Give them time to die
sleep 2
# Force kill any remaining
pkill -9 -f "test-resources-service" 2>/dev/null || true
pkill -9 -f "TestResourcesService" 2>/dev/null || true

# Remove .micronaut directories (including home directory)
echo "🗑️  Removing .micronaut directories..."
find . -name ".micronaut" -type d -exec rm -rf {} + 2>/dev/null || true
rm -rf ~/.micronaut/

# Stop and remove ALL containers (not just specific ones)
echo "🐳 Stopping and removing ALL containers..."
docker stop $(docker ps -q) 2>/dev/null || true
docker rm $(docker ps -aq) 2>/dev/null || true

# Clean up any docker networks created by test containers
echo "🌐 Cleaning up Docker networks..."
docker network prune -f 2>/dev/null || true

echo "✅ Cleanup complete!"
echo ""
echo "To restart test resources, run:"
echo "./gradlew :yappy-orchestrator:engine-integration-test:test --tests '*TestResourcesInitializationTest' --info"