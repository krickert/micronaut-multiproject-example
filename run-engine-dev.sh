#!/bin/bash

# Run Yappy Engine in DEV mode with proper configuration

echo "=== Yappy Engine DEV Launcher ==="
echo ""

# Change to project root
cd "$(dirname "$0")" || exit 1

# Check if Docker services are running
echo "Checking Docker services..."
if ! curl -s http://localhost:8500/v1/status/leader > /dev/null; then
    echo "❌ Consul is not running!"
    echo "Please start Docker services first: cd docker-dev && ./start-dev-env.sh"
    exit 1
fi

if ! curl -s http://localhost:9200 > /dev/null; then
    echo "⚠️  OpenSearch is not running (optional)"
fi

echo "✅ Docker services are running"
echo ""

# Ensure the project is built
echo "Building Yappy Engine..."
./gradlew :yappy-engine:classes :yappy-engine:processResources --quiet

echo ""
echo "Starting Yappy Engine..."
echo "- Environment: dev-apicurio"
echo "- Consul: localhost:8500"
echo "- Kafka: localhost:9094"
echo "- Apicurio: http://localhost:8080/apis/registry/v3"
echo ""

# Set environment variables
export MICRONAUT_ENVIRONMENTS=dev-apicurio
export MICRONAUT_TEST_RESOURCES_ENABLED=false
export MICRONAUT_CONFIG_FILES=classpath:application.yml,classpath:application-dev-apicurio.yml

# Explicitly set service URLs to avoid any property resolution issues
export CONSUL_HOST=localhost
export CONSUL_PORT=8500
export KAFKA_BOOTSTRAP_SERVERS=localhost:9094
export APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3

# Run with Gradle (this is cleaner than trying to manage classpath manually)
exec ./gradlew :yappy-engine:run \
  -Dmicronaut.environments=dev-apicurio \
  -Dmicronaut.test.resources.enabled=false \
  -Dconsul.client.host=localhost \
  -Dconsul.client.port=8500 \
  -Dkafka.bootstrap.servers=localhost:9094 \
  -PdisableTestResources=true \
  --no-daemon