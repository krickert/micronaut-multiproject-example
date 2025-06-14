#!/bin/bash

# Test script to verify all services are working

echo "🔍 Testing YAPPY development environment..."
echo ""

# Test Consul
echo -n "Testing Consul... "
if curl -s http://localhost:8500/v1/status/leader | grep -q '\"'; then
    echo "✅"
else
    echo "❌ Failed"
    exit 1
fi

# Test Kafka
echo -n "Testing Kafka... "
if docker exec $(docker ps -q -f name=kafka) kafka-topics --bootstrap-server localhost:9092 --list &> /dev/null; then
    echo "✅"
else
    echo "❌ Failed"
    exit 1
fi

# Test Apicurio
echo -n "Testing Apicurio... "
if curl -s http://localhost:8080/health | grep -q 'UP'; then
    echo "✅"
else
    echo "❌ Failed"
    exit 1
fi

# Test LocalStack
echo -n "Testing LocalStack... "
if curl -s http://localhost:4566/_localstack/health | grep -q 'running'; then
    echo "✅"
else
    echo "❌ Failed (optional service)"
fi

# Test modules
echo ""
echo "Testing gRPC modules:"

# Test Tika
echo -n "  Tika Parser (50051)... "
if curl -s http://localhost:8081/health | grep -q '"status":"UP"'; then
    echo "✅"
else
    echo "❌ Not running"
fi

# Test Chunker
echo -n "  Chunker (50052)... "
if curl -s http://localhost:8082/health | grep -q '"status":"UP"'; then
    echo "✅"
else
    echo "❌ Not running"
fi

# Test Embedder
echo -n "  Embedder (50053)... "
if curl -s http://localhost:8083/health | grep -q '"status":"UP"'; then
    echo "✅"
else
    echo "❌ Not running"
fi

# Test Echo
echo -n "  Echo (50054)... "
if curl -s http://localhost:8084/health | grep -q '"status":"UP"'; then
    echo "✅"
else
    echo "❌ Not running"
fi

# Test Module
echo -n "  Test Module (50062)... "
if curl -s http://localhost:8085/health | grep -q '"status":"UP"'; then
    echo "✅"
else
    echo "❌ Not running"
fi

echo ""
echo "✅ Infrastructure test complete!"