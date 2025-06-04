#!/bin/bash
set -e

echo "Building engine-embedder container..."

# Ensure we're in the right directory
cd "$(dirname "$0")"

# First, ensure the engine and module are built
echo "Building engine JAR..."
(cd .. && ./gradlew :yappy-engine:shadowJar)

echo "Building embedder module JAR..."
(cd .. && ./gradlew :yappy-modules:embedder:shadowJar)

# Now build the Docker image
echo "Building Docker image..."
docker build \
  -f engine-embedder/Dockerfile \
  -t nas:5000/yappy/engine-embedder:latest \
  -t nas:5000/yappy/engine-embedder:1.0.0-SNAPSHOT \
  ..

echo "Build complete!"
echo ""
echo "To run the container:"
echo "docker run -it --rm \\"
echo "  -e CONSUL_HOST=host.docker.internal \\"
echo "  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "  -p 8083:8080 \\"
echo "  -p 50071:50051 \\"
echo "  -p 50054:50054 \\"
echo "  nas:5000/yappy/engine-embedder:latest"