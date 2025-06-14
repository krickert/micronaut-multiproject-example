#!/bin/bash

# Setup Consul with test pipeline configuration

echo "📝 Setting up Consul with test pipeline configuration..."

# Wait for Consul to be ready
until curl -s http://localhost:8500/v1/status/leader | grep -q '\"'; do
    echo "Waiting for Consul..."
    sleep 2
done

# Create a simple test pipeline configuration
cat > /tmp/test-pipeline.json <<EOF
{
  "clusterName": "dev-cluster",
  "pipelineGraphConfig": {
    "pipelineIds": ["simple-test-pipeline"]
  },
  "pipelineModuleMap": {
    "tika": {
      "moduleUrl": "dns:///tika-parser:50051",
      "connectionConfig": {
        "maxRetryAttempts": 3,
        "retryPolicy": "EXPONENTIAL_BACKOFF",
        "deadlineMs": 30000
      }
    },
    "chunker": {
      "moduleUrl": "dns:///chunker:50052",
      "connectionConfig": {
        "maxRetryAttempts": 3,
        "retryPolicy": "EXPONENTIAL_BACKOFF",
        "deadlineMs": 30000
      }
    },
    "echo": {
      "moduleUrl": "dns:///echo:50054",
      "connectionConfig": {
        "maxRetryAttempts": 3,
        "retryPolicy": "EXPONENTIAL_BACKOFF",
        "deadlineMs": 30000
      }
    },
    "test-module": {
      "moduleUrl": "dns:///test-module:50062",
      "connectionConfig": {
        "maxRetryAttempts": 3,
        "retryPolicy": "EXPONENTIAL_BACKOFF",
        "deadlineMs": 30000
      }
    }
  }
}
EOF

# Create a simple pipeline definition
cat > /tmp/simple-test-pipeline.json <<EOF
{
  "id": "simple-test-pipeline",
  "name": "Simple Test Pipeline",
  "description": "A simple pipeline for testing",
  "pipelineSteps": {
    "step1": {
      "name": "Parse Document",
      "moduleId": "tika",
      "nextSteps": ["step2"]
    },
    "step2": {
      "name": "Chunk Text",
      "moduleId": "chunker",
      "nextSteps": ["step3"]
    },
    "step3": {
      "name": "Echo Result",
      "moduleId": "echo",
      "nextSteps": []
    }
  },
  "kafkaInputDefinition": {
    "topics": ["input-documents"],
    "groupId": "engine-consumer-group"
  }
}
EOF

# Store configurations in Consul
echo "Storing cluster configuration..."
curl -X PUT http://localhost:8500/v1/kv/config/pipeline/clusters/dev-cluster \
  --data-binary @/tmp/test-pipeline.json

echo "Storing pipeline configuration..."
curl -X PUT http://localhost:8500/v1/kv/config/pipeline/simple-test-pipeline \
  --data-binary @/tmp/simple-test-pipeline.json

# Create module registrations
echo ""
echo "Creating module registrations in Consul..."

# Register Tika Parser
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "tika-parser-1",
  "Name": "tika-parser",
  "Tags": ["grpc", "module"],
  "Address": "localhost",
  "Port": 50051,
  "Check": {
    "HTTP": "http://localhost:8081/health",
    "Interval": "10s"
  }
}'

# Register Chunker
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "chunker-1",
  "Name": "chunker",
  "Tags": ["grpc", "module"],
  "Address": "localhost",
  "Port": 50052,
  "Check": {
    "HTTP": "http://localhost:8082/health",
    "Interval": "10s"
  }
}'

# Register Echo
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "echo-1",
  "Name": "echo",
  "Tags": ["grpc", "module"],
  "Address": "localhost",
  "Port": 50054,
  "Check": {
    "HTTP": "http://localhost:8084/health",
    "Interval": "10s"
  }
}'

# Register Test Module
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "test-module-1",
  "Name": "test-module",
  "Tags": ["grpc", "module", "kafka-output"],
  "Address": "localhost",
  "Port": 50062,
  "Check": {
    "HTTP": "http://localhost:8085/health",
    "Interval": "10s"
  }
}'

echo ""
echo "✅ Consul setup complete!"
echo ""
echo "You can view the configuration at:"
echo "  - Consul UI: http://localhost:8500"
echo "  - Services: http://localhost:8500/ui/dc1/services"
echo "  - KV Store: http://localhost:8500/ui/dc1/kv"