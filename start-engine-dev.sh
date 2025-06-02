#!/bin/bash

# Start Yappy Engine in DEV mode - simple and direct

echo "=== Starting Yappy Engine (DEV) ==="
cd "$(dirname "$0")" || exit 1

# Verify Docker services
echo "Checking required services..."
if ! docker ps | grep -q consul-server-dev; then
    echo "❌ Consul container not running!"
    echo "Run: cd docker-dev && ./start-dev-env.sh"
    exit 1
fi

if ! docker ps | grep -q kafka; then
    echo "❌ Kafka container not running!"
    exit 1
fi

echo "✅ Docker services OK"

# Kill any existing test resources server
echo "Ensuring test resources are not running..."
pkill -f "test-resources-service" 2>/dev/null || true

# Set all environment variables
export MICRONAUT_ENVIRONMENTS=dev-apicurio
export MICRONAUT_TEST_RESOURCES_ENABLED=false
export MICRONAUT_LAUNCH_TEST_RESOURCES=false
export CONSUL_HOST=localhost
export CONSUL_PORT=8500
export KAFKA_BOOTSTRAP_SERVERS=localhost:9094
export APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3

echo ""
echo "Configuration:"
echo "  Environment: dev-apicurio"
echo "  Consul: $CONSUL_HOST:$CONSUL_PORT"
echo "  Kafka: $KAFKA_BOOTSTRAP_SERVERS"
echo "  Apicurio: $APICURIO_REGISTRY_URL"
echo ""

# Run with Gradle but prevent test resources
./gradlew :yappy-engine:run \
  -x internalStartTestResourcesService \
  -PskipTestResources=true \
  --no-daemon \
  -Dmicronaut.test.resources.enabled=false \
  -Dmicronaut.environments=dev-apicurio