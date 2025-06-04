#!/bin/bash

# Multi-arch build script for embedder with CUDA support on AMD64

set -e

REGISTRY="${DOCKER_REGISTRY:-nas:5000}"
NAMESPACE="${DOCKER_NAMESPACE:-yappy}"
MODULE_NAME="embedder"
MODULE_PATH="yappy-modules:embedder"

echo "Building multi-arch embedder for registry: $REGISTRY/$NAMESPACE"

# Navigate to parent project
cd /Users/krickert/IdeaProjects/yappy-work

# Build ARM64 version
echo "Building ARM64 version..."
./gradlew :${MODULE_PATH}:shadowJar -Dos.arch=aarch64

# Find the built jar
ARM_JAR=$(find yappy-modules/$MODULE_NAME/build/libs -name "*-all.jar" | head -1)
if [ -z "$ARM_JAR" ]; then
    echo "Error: Could not find ARM64 shadowJar"
    exit 1
fi

# Copy ARM jar to a specific location
cp "$ARM_JAR" "yappy-modules/$MODULE_NAME/build/libs/embedder-arm64-all.jar"

# Clean and build AMD64 version with CUDA
echo "Building AMD64 version with CUDA support..."
./gradlew :${MODULE_PATH}:clean :${MODULE_PATH}:shadowJar -Dos.arch=amd64

# Find the built jar
AMD_JAR=$(find yappy-modules/$MODULE_NAME/build/libs -name "*-all.jar" | grep -v arm64 | head -1)
if [ -z "$AMD_JAR" ]; then
    echo "Error: Could not find AMD64 shadowJar"
    exit 1
fi

# Copy AMD jar to a specific location
cp "$AMD_JAR" "yappy-modules/$MODULE_NAME/build/libs/embedder-amd64-all.jar"

# Create Dockerfile for multi-arch build
MODULE_DIR="yappy-modules/$MODULE_NAME"
cat > $MODULE_DIR/Dockerfile <<'EOF'
FROM eclipse-temurin:21-jre-alpine AS base
RUN apk add --no-cache curl

FROM base AS arm64
COPY build/libs/embedder-arm64-all.jar /app/app.jar

FROM base AS amd64
# Install CUDA runtime libraries for PyTorch
RUN apk add --no-cache \
    curl \
    gcompat \
    libgomp \
    && mkdir -p /usr/local/cuda/lib64

# Copy the AMD64 jar with CUDA support
COPY build/libs/embedder-amd64-all.jar /app/app.jar

# Final stage - will use the appropriate architecture
FROM ${TARGETARCH:-arm64}
WORKDIR /app
EXPOSE 8080

# Set environment variables for PyTorch CUDA
ENV LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH
ENV PYTORCH_CUDA_ALLOC_CONF=garbage_collection_threshold:0.6,max_split_size_mb:128

CMD ["java", "-jar", "app.jar"]
EOF

# Create buildx builder if it doesn't exist
if ! docker buildx ls | grep -q multiarch-builder; then
    echo "Creating buildx builder..."
    docker buildx create --name multiarch-builder --use
fi

# Use the multiarch builder
docker buildx use multiarch-builder

# Build for both architectures
echo "Building multi-arch image..."
docker buildx build \
    --platform linux/amd64,linux/arm64 \
    --tag $REGISTRY/$NAMESPACE/$MODULE_NAME:latest \
    --push \
    $MODULE_DIR

echo "Multi-arch embedder image built and pushed to $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"

# For local testing, also build for current platform
echo "Building local image for current platform..."
docker build -t $REGISTRY/$NAMESPACE/$MODULE_NAME:latest-local $MODULE_DIR

echo ""
echo "Multi-arch build complete!"
echo "- Registry image (multi-arch): $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"
echo "- Local image (current arch): $REGISTRY/$NAMESPACE/$MODULE_NAME:latest-local"
echo ""
echo "To inspect the multi-arch manifest:"
echo "  docker buildx imagetools inspect $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"