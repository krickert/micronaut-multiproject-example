#!/bin/bash

# Test script to verify DEV environment setup

echo "Testing Yappy Engine in DEV mode (without test resources)..."
cd "$(dirname "$0")/.." || exit 1

# Ensure Docker environment is running
echo "Checking Docker services..."
cd docker-dev && ./status-dev-env.sh
cd ..

echo ""
echo "Testing engine startup with DEV configuration..."
echo "This will attempt to start the engine and check for errors..."
echo ""

# Run the engine with timeout to see if it starts successfully
timeout 30s bash -c "
export MICRONAUT_ENVIRONMENTS=dev-apicurio
export MICRONAUT_TEST_RESOURCES_ENABLED=false
./gradlew :yappy-engine:run 2>&1 | tee /tmp/yappy-engine-dev.log
"

# Check if engine started successfully
if grep -q "Startup completed" /tmp/yappy-engine-dev.log; then
    echo ""
    echo "✓ Engine started successfully!"
elif grep -q "ERROR" /tmp/yappy-engine-dev.log; then
    echo ""
    echo "✗ Engine failed to start. Errors found:"
    grep "ERROR" /tmp/yappy-engine-dev.log | head -10
else
    echo ""
    echo "⚠️  Engine startup status unclear. Check the logs at /tmp/yappy-engine-dev.log"
fi

# Check if test resources were activated (they shouldn't be)
if grep -q "test-resources-service" /tmp/yappy-engine-dev.log; then
    echo ""
    echo "⚠️  WARNING: Test resources appear to be active despite settings!"
else
    echo "✓ Test resources are properly disabled"
fi

# Check key services connectivity from logs
echo ""
echo "Service connectivity status from logs:"
if grep -q "Connected to Consul" /tmp/yappy-engine-dev.log || grep -q "ConsulKvService initialized" /tmp/yappy-engine-dev.log; then
    echo "✓ Consul connection established"
fi
if grep -q "KafkaListenerManager.*initialized" /tmp/yappy-engine-dev.log; then
    echo "✓ Kafka listener manager initialized"
fi
if grep -q "apicurio.*registry.*url" /tmp/yappy-engine-dev.log; then
    echo "✓ Apicurio configuration loaded"
fi

echo ""
echo "Log file saved to: /tmp/yappy-engine-dev.log"