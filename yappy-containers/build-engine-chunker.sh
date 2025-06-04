#!/bin/bash
set -e

echo "Building engine-chunker container..."

# Ensure we're in the right directory
cd "$(dirname "$0")"

# First, ensure the engine and module are built
echo "Building engine JAR..."
(cd .. && ./gradlew :yappy-engine:shadowJar)

echo "Building chunker module JAR..."
(cd .. && ./gradlew :yappy-modules:chunker:shadowJar)

# Now build the Docker image
echo "Building Docker image..."
docker build \
  -f engine-chunker/Dockerfile \
  -t localhost:5000/yappy/engine-chunker:latest \
  -t localhost:5000/yappy/engine-chunker:1.0.0-SNAPSHOT \
  ..

echo "Build complete!"
echo ""
echo "To run the container:"
echo "docker run -it --rm \\"
echo "  -e CONSUL_HOST=host.docker.internal \\"
echo "  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "  -p 8082:8080 \\"
echo "  -p 50061:50051 \\"
echo "  -p 50053:50053 \\"
echo "  localhost:5000/yappy/engine-chunker:latest"