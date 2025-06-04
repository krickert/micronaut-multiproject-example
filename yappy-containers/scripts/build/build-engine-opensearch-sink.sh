#!/bin/bash
set -e

echo "Building engine-opensearch-sink container..."

# Ensure we're in the right directory
cd "$(dirname "$0")"

# First, ensure the engine and module are built
echo "Building engine JAR..."
(cd .. && ./gradlew :yappy-engine:shadowJar)

echo "Building opensearch-sink module JAR..."
(cd .. && ./gradlew :yappy-modules:opensearch-sink:shadowJar)

# Now build the Docker image
echo "Building Docker image..."
docker build \
  -f engine-opensearch-sink/Dockerfile \
  -t nas:5000/yappy/engine-opensearch-sink:latest \
  -t nas:5000/yappy/engine-opensearch-sink:1.0.0-SNAPSHOT \
  ..

echo "Build complete!"
echo ""
echo "To run the container:"
echo "docker run -it --rm \\"
echo "  -e CONSUL_HOST=host.docker.internal \\"
echo "  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "  -e OPENSEARCH_HOSTS=host.docker.internal:9200 \\"
echo "  -p 8084:8080 \\"
echo "  -p 50081:50051 \\"
echo "  -p 50055:50055 \\"
echo "  nas:5000/yappy/engine-opensearch-sink:latest"