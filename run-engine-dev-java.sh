#!/bin/bash

# Run Yappy Engine directly with Java (bypassing Gradle test resources)

echo "=== Yappy Engine DEV Launcher (Direct Java) ==="
echo ""

# Change to project root
cd "$(dirname "$0")" || exit 1
PROJECT_ROOT=$(pwd)

# Check if Docker services are running
echo "Checking Docker services..."
if ! curl -s http://localhost:8500/v1/status/leader > /dev/null; then
    echo "❌ Consul is not running!"
    echo "Please start Docker services first: cd docker-dev && ./start-dev-env.sh"
    exit 1
fi
echo "✅ Docker services are running"
echo ""

# Build the application
echo "Building application..."
./gradlew :yappy-engine:build -x test --quiet || {
    echo "❌ Build failed!"
    exit 1
}

# Find the main JAR file
MAIN_JAR=$(find yappy-engine/build/libs -name "*-all.jar" -o -name "yappy-engine-*.jar" | grep -v sources | grep -v javadoc | head -1)
if [ -z "$MAIN_JAR" ]; then
    echo "❌ Could not find application JAR!"
    echo "Looking in: yappy-engine/build/libs"
    ls -la yappy-engine/build/libs/
    exit 1
fi

echo "Using JAR: $MAIN_JAR"
echo ""
echo "Starting Yappy Engine..."
echo "- Environment: dev-apicurio"
echo "- Consul: localhost:8500"
echo "- Kafka: localhost:9094" 
echo "- Apicurio: http://localhost:8080/apis/registry/v3"
echo ""

# Run with explicit configuration
exec java \
  -Dmicronaut.environments=dev-apicurio \
  -Dmicronaut.test.resources.enabled=false \
  -Dconsul.client.host=localhost \
  -Dconsul.client.port=8500 \
  -Dkafka.bootstrap.servers=localhost:9094 \
  -DKAFKA_BOOTSTRAP_SERVERS=localhost:9094 \
  -Dapicurio.registry.url=http://localhost:8080/apis/registry/v3 \
  -Dlogback.configurationFile=$PROJECT_ROOT/yappy-engine/build/resources/main/logback.xml \
  -jar "$MAIN_JAR"