#!/bin/bash
set -e

echo "Updating all build scripts to use registry from gradle.properties..."

# Read registry configuration
REGISTRY=$(grep "docker.registry" gradle.properties | cut -d'=' -f2 | tr -d ' ')
NAMESPACE=$(grep "docker.namespace" gradle.properties | cut -d'=' -f2 | tr -d ' ')

echo "Using registry: ${REGISTRY}/${NAMESPACE}"

# Update all build scripts
for script in build-engine-*.sh; do
    if [ -f "$script" ]; then
        echo "Updating $script..."
        # Replace localhost:5000 with the configured registry
        sed -i.bak "s|localhost:5000/yappy|${REGISTRY}/${NAMESPACE}|g" "$script"
        rm "${script}.bak"
    fi
done

# Also update the build-all script
if [ -f "build-all-containers.sh" ]; then
    echo "Updating build-all-containers.sh..."
    sed -i.bak "s|localhost:5000/yappy|${REGISTRY}/${NAMESPACE}|g" "build-all-containers.sh"
    rm "build-all-containers.sh.bak"
fi

# Update validation scripts
for script in validate-*.sh test-*.sh; do
    if [ -f "$script" ]; then
        echo "Updating $script..."
        sed -i.bak "s|localhost:5000/yappy|${REGISTRY}/${NAMESPACE}|g" "$script"
        rm "${script}.bak"
    fi
done

echo ""
echo "All scripts updated to use: ${REGISTRY}/${NAMESPACE}"
echo ""
echo "You can now rebuild all containers with:"
echo "./build-all-containers.sh"