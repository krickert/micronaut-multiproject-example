#!/bin/bash

# Push yappy images to registry
# Make sure Docker is configured to trust the insecure registry first!

REGISTRY="${DOCKER_REGISTRY:-nas:5000}"
NAMESPACE="${DOCKER_NAMESPACE:-yappy}"

echo "Pushing images to $REGISTRY/$NAMESPACE..."
echo ""
echo "NOTE: If you get an error about HTTPS, you need to:"
echo "1. Open Docker Desktop preferences"
echo "2. Go to Docker Engine settings"
echo "3. Add this to the configuration:"
echo '   "insecure-registries": ["nas:5000"]'
echo "4. Click 'Apply & restart'"
echo ""

# List of images to push
IMAGES=(
    "tika-parser"
    "chunker"
    "echo"
    "opensearch-sink"
    "test-connector"
)

for IMAGE in "${IMAGES[@]}"; do
    echo "Pushing $IMAGE..."
    docker push "$REGISTRY/$NAMESPACE/$IMAGE:latest"
    if [ $? -eq 0 ]; then
        echo "✓ Successfully pushed $IMAGE"
    else
        echo "✗ Failed to push $IMAGE"
    fi
    echo ""
done

echo "Done!"
echo ""
echo "To verify images in registry:"
echo "  curl http://$REGISTRY/v2/_catalog"
echo "  curl http://$REGISTRY/v2/$NAMESPACE/tika-parser/tags/list"