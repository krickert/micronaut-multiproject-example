#!/bin/bash

# Test script for manual registration testing
# This assumes services are running locally

echo "Building registration CLI..."
cd "$(dirname "$0")"
./gradlew clean build

echo ""
echo "Testing registration of tika-parser module..."
echo "Command: java -jar build/libs/yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \\"
echo "  --module-endpoint localhost:50051 \\"
echo "  --engine-endpoint localhost:50050 \\"
echo "  --instance-name tika-parser-manual-test \\"
echo "  --health-type GRPC \\"
echo "  --version 1.0.0"
echo ""

java -jar build/libs/yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \
  --module-endpoint localhost:50051 \
  --engine-endpoint localhost:50050 \
  --instance-name tika-parser-manual-test \
  --health-type GRPC \
  --version 1.0.0

echo ""
echo "Exit code: $?"