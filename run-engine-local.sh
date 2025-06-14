#!/bin/bash

# Run YAPPY Engine locally with dev environment

echo "🚀 Starting YAPPY Engine locally..."

# Export all required environment variables
export MICRONAUT_ENVIRONMENTS=dev
export CONSUL_CLIENT_HOST=localhost
export CONSUL_CLIENT_PORT=8500
export KAFKA_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_BROKERS=localhost:9092
export KAFKA_PRODUCERS_DEFAULT_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_CONSUMERS_DEFAULT_BOOTSTRAP_SERVERS=localhost:9092
export KAFKA_ENABLED=true
export APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3
export KAFKA_PRODUCERS_DEFAULT_APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3
export KAFKA_CONSUMERS_DEFAULT_APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3
export AWS_ENDPOINT=http://localhost:4566
export AWS_REGION=us-east-1
export APP_CONFIG_CLUSTER_NAME=dev-cluster
export YAPPY_CLUSTER_NAME=dev-cluster
export ENGINE_CLUSTER_NAME=dev-cluster
export GRPC_SERVER_PORT=50070
export GRPC_SERVER_HOST=0.0.0.0
export MICRONAUT_SERVER_PORT=8091
export MICRONAUT_SERVER_HOST=0.0.0.0

# Additional config that might be needed
export APP_CONFIG_CONSUL_KEY_PREFIXES_PIPELINE_CLUSTERS=config/pipeline/clusters/
export APP_CONFIG_CONSUL_KEY_PREFIXES_SCHEMA_VERSIONS=config/pipeline/schemas/
export APP_CONFIG_CONSUL_KEY_PREFIXES_WHITELISTS=config/pipeline/whitelists/
export APP_CONFIG_CONSUL_WATCH_SECONDS=5

# Disable test resources when running locally
export MICRONAUT_TEST_RESOURCES_ENABLED=false

echo "Environment variables set:"
echo "  CONSUL: $CONSUL_CLIENT_HOST:$CONSUL_CLIENT_PORT"
echo "  KAFKA: $KAFKA_BOOTSTRAP_SERVERS"
echo "  APICURIO: $APICURIO_REGISTRY_URL"
echo "  AWS: $AWS_ENDPOINT"
echo "  CLUSTER: $APP_CONFIG_CLUSTER_NAME"
echo ""
echo "Starting engine..."

# Check if debug mode is requested
if [ "$1" = "--debug" ]; then
    echo "Starting in DEBUG mode on port 5000..."
    export JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5000"
    echo "You can attach your debugger to localhost:5000"
fi

# Run the engine
./gradlew :yappy-engine:run -x internalStartTestResourcesService -Dmicronaut.test.resources.enabled=false