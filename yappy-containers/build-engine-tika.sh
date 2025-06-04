#!/bin/bash
set -e

echo "Building engine-tika-parser container..."

# Ensure we're in the right directory
cd "$(dirname "$0")"

# First, ensure the engine and module are built
echo "Building engine JAR..."
(cd .. && ./gradlew :yappy-engine:shadowJar)

echo "Building tika-parser module JAR..."
(cd .. && ./gradlew :yappy-modules:tika-parser:shadowJar)

# Now build the Docker image using docker directly (simpler for testing)
echo "Building Docker image..."
docker build \
  -f engine-tika-parser/Dockerfile \
  -t localhost:5000/yappy/engine-tika-parser:latest \
  -t localhost:5000/yappy/engine-tika-parser:1.0.0-SNAPSHOT \
  ..

echo "Build complete!"
echo ""
echo "To run the container:"
echo "docker run -it --rm \\"
echo "  -e CONSUL_HOST=host.docker.internal \\"
echo "  -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "  -p 8081:8080 \\"
echo "  -p 50051:50051 \\"
echo "  -p 50052:50052 \\"
echo "  localhost:5000/yappy/engine-tika-parser:latest"