#!/bin/bash

# Build remaining containers
set -e

REGISTRY="${DOCKER_REGISTRY:-nas:5000}"
NAMESPACE="${DOCKER_NAMESPACE:-yappy}"

echo "Building remaining containers for registry: $REGISTRY/$NAMESPACE"

# Function to build a module
build_module() {
    local MODULE_NAME=$1
    local MAIN_CLASS=$2
    local MODULE_PATH=$3
    
    echo "Building $MODULE_NAME..."
    
    # Navigate to parent project and build the jar
    cd /Users/krickert/IdeaProjects/yappy-work
    ./gradlew :${MODULE_PATH}:shadowJar
    
    # Find the built jar
    JAR_FILE=$(find yappy-modules/$MODULE_NAME/build/libs -name "*-all.jar" | head -1)
    if [ -z "$JAR_FILE" ]; then
        echo "Error: Could not find shadowJar for $MODULE_NAME"
        return 1
    fi
    
    # Create Dockerfile in the module directory
    MODULE_DIR="yappy-modules/$MODULE_NAME"
    cat > $MODULE_DIR/Dockerfile <<EOF
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY build/libs/*-all.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
EOF
    
    # Build and tag the image from the module directory
    docker build -t $REGISTRY/$NAMESPACE/$MODULE_NAME:latest $MODULE_DIR
    
    echo "Built $REGISTRY/$NAMESPACE/$MODULE_NAME:latest"
}

# Build opensearch-sink
build_module "opensearch-sink" "com.krickert.yappy.modules.opensearchsink.OpensearchSinkApplication" "yappy-modules:opensearch-sink"

# Build test-connector
build_module "yappy-connector-test-server" "com.krickert.yappy.test.YappyTestConnectorApplication" "yappy-modules:yappy-connector-test-server"

echo "Build complete!"