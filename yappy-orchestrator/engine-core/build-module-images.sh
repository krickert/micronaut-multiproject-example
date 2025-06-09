#!/bin/bash

# Script to build Docker images for module integration tests
# Run this before running ModuleIntegrationTest

echo "Building module Docker images for integration tests..."

# Change to project root
cd ../..

# Build chunker module and Docker image
echo "Building chunker module..."
./gradlew :yappy-modules:chunker:dockerBuild

# Build tika-parser module and Docker image  
echo "Building tika-parser module..."
./gradlew :yappy-modules:tika-parser:dockerBuild

echo "Done! You can now run the ModuleIntegrationTest."