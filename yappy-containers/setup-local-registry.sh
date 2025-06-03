#!/bin/bash

# Setup local Docker registry for development

# Configuration
REGISTRY_HOST="${DOCKER_REGISTRY_HOST:-localhost}"
REGISTRY_PORT="${DOCKER_REGISTRY_PORT:-5000}"
REGISTRY_NAME="${DOCKER_REGISTRY_NAME:-registry}"
REGISTRY_VOLUME="${DOCKER_REGISTRY_VOLUME:-registry-data}"
START_LOCAL_REGISTRY="${START_LOCAL_REGISTRY:-true}"

echo "Docker Registry Configuration:"
echo "  Host: $REGISTRY_HOST"
echo "  Port: $REGISTRY_PORT"
echo "  Container Name: $REGISTRY_NAME"
echo "  Volume: $REGISTRY_VOLUME"
echo "  Start Local Registry: $START_LOCAL_REGISTRY"
echo ""

# Check if we should start a local registry
if [ "$START_LOCAL_REGISTRY" = "true" ]; then
    echo "Setting up local Docker registry..."
    
    # Check if registry is already running
    if docker ps | grep -q "$REGISTRY_NAME"; then
        echo "Registry '$REGISTRY_NAME' is already running"
    else
        # Check if container exists but is stopped
        if docker ps -a | grep -q "$REGISTRY_NAME"; then
            echo "Starting existing registry container '$REGISTRY_NAME'..."
            docker start "$REGISTRY_NAME"
        else
            # Start local registry
            echo "Creating new registry container '$REGISTRY_NAME'..."
            docker run -d \
                -p "${REGISTRY_PORT}:5000" \
                --restart=always \
                --name "$REGISTRY_NAME" \
                -v "${REGISTRY_VOLUME}:/var/lib/registry" \
                registry:2
        fi
        
        if [ $? -eq 0 ]; then
            echo "Local registry started at ${REGISTRY_HOST}:${REGISTRY_PORT}"
        else
            echo "Failed to start local registry. It may already be running or port $REGISTRY_PORT is in use."
        fi
    fi
else
    echo "Skipping local registry startup (using external registry at ${REGISTRY_HOST}:${REGISTRY_PORT})"
fi

# Configure Docker to accept the insecure registry (if needed)
REGISTRY_URL="${REGISTRY_HOST}:${REGISTRY_PORT}"
if [ "$REGISTRY_HOST" != "localhost" ] && [ "$REGISTRY_HOST" != "127.0.0.1" ]; then
    echo ""
    echo "Using external registry at $REGISTRY_URL"
    echo "Please ensure your Docker daemon is configured to trust this registry."
    
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "On macOS, add '$REGISTRY_URL' to insecure registries in Docker Desktop preferences."
    else
        echo "On Linux, add '$REGISTRY_URL' to 'insecure-registries' in /etc/docker/daemon.json"
    fi
else
    if [[ "$OSTYPE" == "darwin"* ]]; then
        echo "On macOS, Docker Desktop handles insecure registries through its UI"
        echo "Please add '$REGISTRY_URL' to insecure registries in Docker Desktop preferences if not already done"
    else
        # Linux configuration
        DOCKER_CONFIG="/etc/docker/daemon.json"
        if [ -f "$DOCKER_CONFIG" ]; then
            echo "Docker daemon.json already exists, please manually add $REGISTRY_URL to insecure-registries"
        else
            echo "Creating Docker daemon configuration..."
            sudo tee /etc/docker/daemon.json > /dev/null <<EOF
{
  "insecure-registries": ["$REGISTRY_URL"]
}
EOF
            echo "Restarting Docker daemon..."
            sudo systemctl restart docker
        fi
    fi
fi

# Test the registry
echo ""
echo "Testing registry at $REGISTRY_URL..."

# Pull a small test image
docker pull hello-world >/dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "❌ Failed to pull test image. Please check your internet connection."
    exit 1
fi

# Tag it for our registry
docker tag hello-world "${REGISTRY_URL}/hello-world" >/dev/null 2>&1
if [ $? -ne 0 ]; then
    echo "❌ Failed to tag test image."
    exit 1
fi

# Try to push to the registry
docker push "${REGISTRY_URL}/hello-world" >/dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✅ Registry test successful!"
    # Clean up
    docker rmi "${REGISTRY_URL}/hello-world" >/dev/null 2>&1
    docker rmi hello-world >/dev/null 2>&1
else
    echo "❌ Registry test failed. Please check your configuration."
    echo ""
    echo "Common issues:"
    echo "1. Registry is not accessible at $REGISTRY_URL"
    echo "2. Docker daemon doesn't trust the registry (add to insecure-registries)"
    echo "3. Network/firewall issues preventing access"
    exit 1
fi

echo ""
echo "Registry is ready for use at $REGISTRY_URL!"
echo ""
echo "To use this registry with yappy-containers, set:"
echo "  export DOCKER_REGISTRY=$REGISTRY_URL"
echo "Or add to gradle.properties:"
echo "  docker.registry=$REGISTRY_URL"