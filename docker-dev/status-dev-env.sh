#!/bin/bash

# Check status of Docker development environment for Yappy

echo "Checking Yappy development environment status..."
cd "$(dirname "$0")" || exit 1

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running."
    exit 1
fi

echo ""
echo "Container Status:"
echo "-----------------"
docker-compose ps

echo ""
echo "Service Health Checks:"
echo "---------------------"

# Check Consul
echo -n "Consul:      "
if curl -s "http://localhost:8500/v1/status/leader" | grep -q ":"; then
    echo "✓ Running (http://localhost:8500)"
else
    echo "✗ Not responding"
fi

# Check Kafka
echo -n "Kafka:       "
if docker exec kafka bash -c "if [ -f /opt/kafka/bin/kafka-topics.sh ]; then /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list; else kafka-topics.sh --bootstrap-server kafka:9092 --list; fi" &> /dev/null; then
    echo "✓ Running (localhost:9094)"
else
    echo "✗ Not responding"
fi

# Check Kafka UI
echo -n "Kafka UI:    "
if curl -s "http://localhost:8081" | grep -q "html"; then
    echo "✓ Running (http://localhost:8081)"
else
    echo "✗ Not responding"
fi

# Check Apicurio
echo -n "Apicurio:    "
if curl -s "http://localhost:8080/apis" | grep -q "apiVersion"; then
    echo "✓ Running (http://localhost:8080)"
else
    echo "✗ Not responding"
fi

# Check Moto
echo -n "Moto/Glue:   "
if curl -s "http://localhost:5001" &> /dev/null; then
    echo "✓ Running (http://localhost:5001)"
else
    echo "✗ Not responding"
fi

# Check OpenSearch
echo -n "OpenSearch:  "
if curl -s "http://localhost:9200" | grep -q "version"; then
    echo "✓ Running (http://localhost:9200)"
else
    echo "✗ Not responding"
fi

# Check OpenSearch Dashboards
echo -n "OS Dashboards: "
if curl -s "http://localhost:5601" | grep -q "html"; then
    echo "✓ Running (http://localhost:5601)"
else
    echo "✗ Not responding"
fi

# Check Solr
echo -n "Solr:        "
if curl -s "http://localhost:8983/solr/admin/info/system" | grep -q "solr_home"; then
    echo "✓ Running (http://localhost:8983)"
else
    echo "✗ Not responding"
fi

echo ""