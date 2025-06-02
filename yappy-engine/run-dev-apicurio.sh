#!/bin/bash

# Run Yappy Engine in DEV mode with Apicurio (no test resources)

echo "Starting Yappy Engine in DEV mode with Apicurio Schema Registry..."
echo "Make sure docker-compose is running in ./docker-dev"

# Set environment to use dev-apicurio profile
export MICRONAUT_ENVIRONMENTS=dev-apicurio

# Explicitly disable test resources
export MICRONAUT_TEST_RESOURCES_ENABLED=false

# Optional: Set specific service URLs if needed
# export CONSUL_HOST=localhost
# export CONSUL_PORT=8500
# export KAFKA_BOOTSTRAP_SERVERS=localhost:9094
# export APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3

# Change to the engine directory
cd "$(dirname "$0")"

# Run with gradle
../gradlew run \
  -Dmicronaut.test.resources.enabled=false \
  -Dmicronaut.environments=dev-apicurio