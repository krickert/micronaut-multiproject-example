#!/bin/bash
set -e

echo "Building engine-test-connector container..."

# Ensure we're in the right directory
cd "$(dirname "$0")"

# First, ensure the engine and module are built
echo "Building engine JAR..."
(cd .. && ./gradlew :yappy-engine:shadowJar)

echo "Building test-connector module JAR..."
(cd .. && ./gradlew :yappy-modules:test-connector:shadowJar)

# Now build the Docker image
echo "Building Docker image..."
docker build \
  -f engine-test-connector/Dockerfile \
  -t nas:5000/yappy/engine-test-connector:latest \
  -t nas:5000/yappy/engine-test-connector:1.0.0-SNAPSHOT \
  ..

echo "Build complete!"
echo ""
echo "To run the container:"
echo "docker run -it --rm \\"
echo "  -e CONSUL_HOST=host.docker.internal \\"
echo "  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "  -e TEST_CONNECTOR_MODE=echo \\"
echo "  -p 8085:8080 \\"
echo "  -p 50091:50051 \\"
echo "  -p 50059:50059 \\"
echo "  nas:5000/yappy/engine-test-connector:latest"