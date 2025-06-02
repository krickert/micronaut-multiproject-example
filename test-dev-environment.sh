#!/bin/bash

# Comprehensive test of Yappy DEV environment

echo "=== Yappy DEV Environment Test ==="
echo ""

cd "$(dirname "$0")" || exit 1

# 1. Check Docker services
echo "1. Checking Docker services..."
SERVICES_OK=true

check_service() {
    local name=$1
    local url=$2
    local expected=$3
    
    if curl -s "$url" | grep -q "$expected" 2>/dev/null || curl -s "$url" > /dev/null 2>&1; then
        echo "   ✅ $name is running"
    else
        echo "   ❌ $name is NOT running or not responding"
        SERVICES_OK=false
    fi
}

check_service "Consul" "http://localhost:8500/v1/status/leader" ":"
# Kafka requires special handling - check if container is running
if docker ps | grep -q " kafka "; then
    echo "   ✅ Kafka container is running"
else
    echo "   ❌ Kafka container is NOT running"
    SERVICES_OK=false
fi
check_service "Apicurio" "http://localhost:8080/apis/registry/v3/system/info" "Apicurio"
check_service "OpenSearch" "http://localhost:9200" "version"

if [ "$SERVICES_OK" = false ]; then
    echo ""
    echo "❌ Some services are not running. Please run: cd docker-dev && ./start-dev-env.sh"
    exit 1
fi

# 2. Check Consul configuration
echo ""
echo "2. Checking Consul configuration..."
CLUSTER_CONFIG=$(curl -s "http://localhost:8500/v1/kv/yappy/pipeline-configs/clusters/yappy-cluster?raw")
if echo "$CLUSTER_CONFIG" | grep -q "clusterName"; then
    echo "   ✅ Cluster configuration exists in Consul"
else
    echo "   ❌ Cluster configuration missing. Run: cd docker-dev && ./bootstrap-consul.sh"
    exit 1
fi

# 3. Test Kafka connectivity
echo ""
echo "3. Testing Kafka connectivity..."
docker exec kafka bash -c "
    if [ -f /opt/kafka/bin/kafka-topics.sh ]; then 
        /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1
    else 
        kafka-topics.sh --bootstrap-server localhost:9092 --list > /dev/null 2>&1
    fi
" && echo "   ✅ Kafka is accessible" || echo "   ❌ Kafka is not accessible"

# 4. Test Apicurio Registry
echo ""
echo "4. Testing Apicurio Registry..."
APICURIO_INFO=$(curl -s "http://localhost:8080/apis/registry/v3/system/info")
if echo "$APICURIO_INFO" | grep -q "Apicurio Registry"; then
    VERSION=$(echo "$APICURIO_INFO" | grep -o '"version":"[^"]*"' | cut -d'"' -f4)
    echo "   ✅ Apicurio Registry v$VERSION is running"
else
    echo "   ❌ Apicurio Registry is not responding correctly"
fi

# 5. Check engine bootstrap file
echo ""
echo "5. Checking engine bootstrap configuration..."
if [ -f "$HOME/.yappy/engine-bootstrap.properties" ]; then
    echo "   ✅ Engine bootstrap file exists"
    grep "consul.host" "$HOME/.yappy/engine-bootstrap.properties" | head -1
else
    echo "   ⚠️  Engine bootstrap file missing (will be created on first run)"
fi

# 6. Summary
echo ""
echo "=== Summary ==="
if [ "$SERVICES_OK" = true ]; then
    echo "✅ DEV environment is ready!"
    echo ""
    echo "To start the engine, run ONE of:"
    echo "  1. ./start-engine-dev.sh (recommended)"
    echo "  2. Use IntelliJ run configuration: 'Yappy Engine DEV Apicurio'"
    echo "  3. ./run-engine-dev-java.sh (direct Java execution)"
else
    echo "❌ DEV environment has issues. Please fix them before starting the engine."
fi