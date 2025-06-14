#!/bin/bash

# Start development environment for YAPPY Engine

echo "🚀 Starting YAPPY development environment..."

# Check if docker compose is available
if ! docker compose version &> /dev/null; then
    echo "❌ docker compose not found. Please install docker compose."
    exit 1
fi

# Start infrastructure services
echo "📦 Starting infrastructure services..."
docker compose -f docker-compose.dev.yml up -d consul kafka apicurio localstack

# Wait for services to be healthy
echo "⏳ Waiting for services to be ready..."
sleep 10

# Check Consul
echo -n "Checking Consul... "
until curl -s http://localhost:8500/v1/status/leader | grep -q '\"'; do
    sleep 2
done
echo "✅"

# Check Kafka
echo -n "Checking Kafka... "
until docker compose -f docker-compose.dev.yml exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list &> /dev/null; do
    sleep 2
done
echo "✅"

# Check Apicurio
echo -n "Checking Apicurio... "
until curl -s http://localhost:8080/health | grep -q 'UP'; do
    sleep 2
done
echo "✅"

# Start module containers
echo "📦 Starting module containers..."
docker compose -f docker-compose.dev.yml up -d tika-parser chunker embedder echo test-module

# Wait for modules to be ready
sleep 5

echo "✅ Infrastructure is ready!"
echo ""
echo "Services running:"
echo "  - Consul UI: http://localhost:8500"
echo "  - Kafka: localhost:9092"
echo "  - Apicurio Registry: http://localhost:8080"
echo "  - LocalStack (AWS): http://localhost:4566"
echo ""
echo "Modules running:"
echo "  - Tika Parser: localhost:50051"
echo "  - Chunker: localhost:50052"
echo "  - Embedder: localhost:50053"
echo "  - Echo: localhost:50054"
echo "  - Test Module: localhost:50062"
echo ""
echo "Now you can start the engine with:"
echo "  ./gradlew :yappy-engine:run"
echo ""
echo "With these environment variables:"
echo "  export CONSUL_CLIENT_HOST=localhost"
echo "  export CONSUL_CLIENT_PORT=8500"
echo "  export KAFKA_BOOTSTRAP_SERVERS=localhost:9092"
echo "  export KAFKA_BROKERS=localhost:9092"
echo "  export KAFKA_ENABLED=true"
echo "  export APICURIO_REGISTRY_URL=http://localhost:8080/apis/registry/v3"
echo "  export AWS_ENDPOINT=http://localhost:4566"
echo "  export APP_CONFIG_CLUSTER_NAME=dev-cluster"
echo ""
echo "To stop all services: docker compose -f docker-compose.dev.yml down"