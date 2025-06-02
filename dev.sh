#!/bin/bash

# Simple DEV launcher for Yappy Engine

cd "$(dirname "$0")" || exit 1

# Quick check for Docker services
if ! docker ps | grep -q consul-server-dev; then
    echo "Starting Docker services..."
    cd docker-dev && ./start-dev-env.sh
    cd ..
fi

# Ensure Consul is bootstrapped
if ! curl -s "http://localhost:8500/v1/kv/yappy/pipeline-configs/clusters/yappy-cluster?raw" | grep -q "clusterName" 2>/dev/null; then
    echo "Bootstrapping Consul..."
    cd docker-dev && ./bootstrap-consul.sh
    cd ..
fi

echo "Starting Yappy Engine in DEV mode..."
echo "- Consul: localhost:8500"
echo "- Kafka: localhost:9094"
echo "- Apicurio: http://localhost:8080/apis/registry/v3"
echo ""

# Run with dev environment - test resources will be disabled by build.gradle.kts
export MICRONAUT_ENVIRONMENTS=dev-apicurio
export KAFKA_BOOTSTRAP_SERVERS=localhost:9094
export DISABLE_TEST_RESOURCES=true

# Use the custom runDev task which ensures test resources are disabled
./gradlew :yappy-engine:runDev \
  --console=plain \
  -PdisableTestResources=true