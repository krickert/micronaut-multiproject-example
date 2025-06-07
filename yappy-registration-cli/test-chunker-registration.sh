#!/bin/bash

# Test script for registering chunker module
# This assumes services are running locally

echo "Building registration CLI..."
cd "$(dirname "$0")"
./gradlew clean build

echo ""
echo "Testing registration of chunker module..."
echo "Command: java -jar build/libs/yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \\"
echo "  --module-endpoint localhost:50052 \\"
echo "  --engine-endpoint localhost:50050 \\"
echo "  --instance-name chunker-manual-test \\"
echo "  --health-type GRPC \\"
echo "  --version 1.0.0"
echo ""

java -jar build/libs/yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \
  --module-endpoint localhost:50052 \
  --engine-endpoint localhost:50050 \
  --instance-name chunker-manual-test \
  --health-type GRPC \
  --version 1.0.0

echo ""
echo "Exit code: $?"