#!/bin/bash

# Check status of Docker development environment for Yappy

echo "Checking Yappy development environment status..."
cd "$(dirname "$0")" || exit 1

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running. Please start Docker first."
    exit 1
fi

echo ""
echo "Container Status:"
echo "================="
docker-compose ps

echo ""
echo "Service Health Checks:"
echo "====================="

# Check NAS Registry (if configured)
if [ -n "${NAS_REGISTRY_HOST:-}" ]; then
    echo -n "NAS Registry:    "
    if curl -s "http://${NAS_REGISTRY_HOST}:5000/v2/" | grep -q "{}"; then
        echo "✓ Running (http://${NAS_REGISTRY_HOST}:5000)"
    else
        echo "✗ Not responding"
    fi
fi

# Check Consul
echo -n "Consul:          "
if curl -s "http://localhost:8500/v1/status/leader" | grep -q ":"; then
    echo "✓ Running (UI: http://localhost:8500)"
else
    echo "✗ Not responding"
fi

# Check Kafka
echo -n "Kafka:           "
if docker exec kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 &> /dev/null; then
    echo "✓ Running"
else
    echo "✗ Not responding"
fi

# Check Kafka UI
echo -n "Kafka UI:        "
if curl -s "http://localhost:8081" &> /dev/null; then
    echo "✓ Running (http://localhost:8081)"
else
    echo "✗ Not responding"
fi

# Check Apicurio
echo -n "Apicurio:        "
if curl -s "http://localhost:8080/health" | grep -q "UP"; then
    echo "✓ Running (http://localhost:8080)"
else
    echo "✗ Not responding"
fi

# Check Moto/Glue
echo -n "Moto/Glue:       "
if curl -s "http://localhost:5001" &> /dev/null; then
    echo "✓ Running"
else
    echo "✗ Not responding"
fi

# Check OpenSearch
echo -n "OpenSearch:      "
if curl -s "http://localhost:9200/_cluster/health" | grep -q "status"; then
    STATUS=$(curl -s "http://localhost:9200/_cluster/health" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
    echo "✓ Running (Status: $STATUS)"
else
    echo "✗ Not responding"
fi

# Check OpenSearch Dashboards
echo -n "OS Dashboards:   "
if curl -s "http://localhost:5601/api/status" | grep -q "available"; then
    echo "✓ Running (http://localhost:5601)"
else
    echo "✗ Not responding"
fi

echo ""
echo "Network Information:"
echo "==================="
docker network inspect yappy-network --format '{{.Name}}: {{.Driver}} ({{.Scope}})' 2>/dev/null || echo "Network 'yappy-network' not found"

echo ""
echo "Volume Information:"
echo "==================="
docker volume ls | grep -E "(kafka-data|opensearch-data|consul-data)" || echo "No volumes found"

echo ""
echo "Resource Usage:"
echo "==============="
docker stats --no-stream --format "table {{.Container}}\t{{.CPUPerc}}\t{{.MemUsage}}" "$(docker-compose ps -q 2>/dev/null)"