#!/bin/bash
set -e

echo "Building Tika Parser Docker container using Micronaut..."
echo "========================================================"

# Navigate to project root
cd ../..

# Build the module JAR first
echo "Building Tika Parser module..."
./gradlew :yappy-modules:tika-parser:shadowJar

# Build the engine
echo "Building Engine..."
./gradlew :yappy-engine:shadowJar

# Build the Docker image
echo "Building Docker image..."
./gradlew :yappy-containers:engine-tika-parser:dockerBuild

# List the image
echo ""
echo "Docker image built:"
docker images | grep "yappy/engine-tika-parser" || echo "Image not found!"

echo ""
echo "To run the container:"
echo "  docker run -p 8080:8080 -p 50051:50051 -p 50053:50053 \\"
echo "    -e YAPPY_CLUSTER_NAME=test-cluster \\"
echo "    -e CONSUL_HOST=host.docker.internal \\"
echo "    -e KAFKA_BOOTSTRAP_SERVERS=host.docker.internal:9092 \\"
echo "    -e APICURIO_REGISTRY_URL=http://host.docker.internal:8081 \\"
echo "    yappy/engine-tika-parser:latest"

echo ""
echo "Or use docker-compose:"
echo "  cd yappy-containers/engine-tika-parser"
echo "  docker-compose -f docker-compose.test.yml up"