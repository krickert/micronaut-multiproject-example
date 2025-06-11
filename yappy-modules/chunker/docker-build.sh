#!/bin/bash
set -e

echo "Building ChunkerApplication Docker image..."

# First run the Gradle tasks to prepare the Docker context
./gradlew buildLayers

# Then build the Docker image manually
cd build/docker/main
docker build -t chunkerapplication:1.0.0-SNAPSHOT -t chunkerapplication:latest .
cd ../../..

echo "Docker image built successfully!"
echo "Images created:"
docker images | grep chunkerapplication