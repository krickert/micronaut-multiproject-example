#!/bin/bash

echo "Verifying services in engine-tika-parser container..."

# Check if container is running
CONTAINER_ID=$(docker ps -q -f name=engine-tika-test)
if [ -z "$CONTAINER_ID" ]; then
    echo "❌ Container not running"
    exit 1
fi

echo "✅ Container is running"

# Check supervisor status
echo ""
echo "Supervisor status:"
docker exec engine-tika-test supervisorctl status

# Check engine health
echo ""
echo "Checking engine health endpoint..."
curl -s http://localhost:8082/health | jq . || echo "❌ Engine health check failed"

# Check engine gRPC port
echo ""
echo "Checking engine gRPC port (50051)..."
nc -zv localhost 50051 2>&1 | grep -q succeeded && echo "✅ Engine gRPC port is open" || echo "❌ Engine gRPC port not accessible"

# Check tika module gRPC port
echo ""
echo "Checking Tika module gRPC port (50052)..."
nc -zv localhost 50052 2>&1 | grep -q succeeded && echo "✅ Tika module gRPC port is open" || echo "❌ Tika module gRPC port not accessible"

# Check logs
echo ""
echo "Recent engine logs:"
docker exec engine-tika-test tail -n 10 /var/log/supervisor/engine.log

echo ""
echo "Recent Tika module logs:"
docker exec engine-tika-test tail -n 10 /var/log/supervisor/tika-parser.log