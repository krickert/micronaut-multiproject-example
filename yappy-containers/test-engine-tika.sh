#!/bin/bash

echo "Starting test environment for engine-tika-parser..."

# First ensure the dev environment is running
echo "Checking if dev environment is running..."
cd ../docker-dev
docker-compose ps | grep -E "(consul|kafka|apicurio)" || {
    echo "Dev environment not running. Starting it..."
    ./start-dev-env.sh
    sleep 10
}

cd ../yappy-containers

# Run the container
echo "Starting engine-tika-parser container..."
docker run -d --rm \
  --name engine-tika-test \
  --network yappy-network \
  -e YAPPY_CLUSTER_NAME=test-cluster \
  -e CONSUL_HOST=consul-dev \
  -e CONSUL_PORT=8500 \
  -e KAFKA_BOOTSTRAP_SERVERS=kafka:9092 \
  -e APICURIO_REGISTRY_URL=http://apicurio:8080 \
  -p 8082:8080 \
  -p 50051:50051 \
  -p 50052:50052 \
  localhost:5000/yappy/engine-tika-parser:latest

echo "Container started. Waiting for services to be ready..."
sleep 10

# Verify services
./verify-services.sh