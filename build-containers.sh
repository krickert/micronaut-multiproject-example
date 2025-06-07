#!/bin/bash

# Build all Docker containers for the new architecture

set -e  # Exit on error

echo "Building YAPPY containers..."

# Build Tika Parser container
echo "Building Tika Parser container..."
./gradlew :yappy-containers:engine-tika-parser:dockerBuild

# Build Chunker container
echo "Building Chunker container..."
./gradlew :yappy-containers:engine-chunker:dockerBuild

# Build Engine container
echo "Building Engine container..."
./gradlew :yappy-containers:engine-standalone:dockerBuild

echo "All containers built successfully!"

# List the images
echo -e "\nBuilt images:"
docker images | grep -E "(yappy-engine|yappy-tika-parser|yappy-chunker)" | head -4

echo -e "\nTo start the services, run:"
echo "  docker-compose up"

echo -e "\nTo run integration tests with real containers:"
echo "  ./gradlew :yappy-integration-test:test"