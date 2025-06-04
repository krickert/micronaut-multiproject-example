#!/bin/bash

# Build embedder with architecture-specific dependencies

set -e

REGISTRY="${DOCKER_REGISTRY:-nas:5000}"
NAMESPACE="${DOCKER_NAMESPACE:-yappy}"
MODULE_NAME="embedder"
MODULE_PATH="yappy-modules:embedder"

echo "Building embedder for registry: $REGISTRY/$NAMESPACE"

# Navigate to parent project
cd /Users/krickert/IdeaProjects/yappy-work

# Build the jar (it will detect current architecture)
echo "Building embedder shadowJar..."
./gradlew :${MODULE_PATH}:shadowJar

# Find the built jar
JAR_FILE=$(find yappy-modules/$MODULE_NAME/build/libs -name "*-all.jar" | head -1)
if [ -z "$JAR_FILE" ]; then
    echo "Error: Could not find shadowJar for $MODULE_NAME"
    exit 1
fi

# Create Dockerfile with multi-stage build for different architectures
MODULE_DIR="yappy-modules/$MODULE_NAME"
cat > $MODULE_DIR/Dockerfile.multiarch <<'EOF'
# Multi-architecture Dockerfile for embedder

# Base image for all architectures
FROM eclipse-temurin:21-jre AS base-amd64
# Install CUDA runtime dependencies for AMD64
RUN apt-get update && apt-get install -y \
    libgomp1 \
    curl \
    && rm -rf /var/lib/apt/lists/*

FROM eclipse-temurin:21-jre-alpine AS base-arm64
# Just install curl for ARM64
RUN apk add --no-cache curl

# Select the appropriate base image based on target architecture
FROM base-${TARGETARCH} AS final

WORKDIR /app

# Copy the jar file
COPY build/libs/*-all.jar app.jar

EXPOSE 8080

# Set JVM options for better container performance
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC"

# For AMD64, set PyTorch environment variables
ENV PYTORCH_CUDA_ALLOC_CONF=garbage_collection_threshold:0.6,max_split_size_mb:128

CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
EOF

# Also create a simple Dockerfile for single-arch builds
cat > $MODULE_DIR/Dockerfile <<EOF
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY build/libs/*-all.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
EOF

# Build for current architecture
echo "Building embedder image for current architecture..."
docker build -t $REGISTRY/$NAMESPACE/$MODULE_NAME:latest $MODULE_DIR

echo "Built $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"
echo ""
echo "To push to registry:"
echo "  docker push $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"
echo ""
echo "For multi-arch build (requires buildx):"
echo "  docker buildx build --platform linux/amd64,linux/arm64 -f $MODULE_DIR/Dockerfile.multiarch -t $REGISTRY/$NAMESPACE/$MODULE_NAME:multiarch --push $MODULE_DIR"