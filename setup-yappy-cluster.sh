#!/bin/bash

# Complete setup script for YAPPY cluster with proper seeding

echo "🚀 Setting up YAPPY cluster with complete configuration..."

# Colors for output
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Wait for Consul to be ready
echo -e "${YELLOW}Waiting for Consul...${NC}"
until curl -s http://localhost:8500/v1/status/leader | grep -q '\"'; do
    sleep 2
done
echo -e "${GREEN}✓ Consul is ready${NC}"

# Create Kafka topics
echo -e "${YELLOW}Creating Kafka topics...${NC}"
docker exec -it $(docker ps -q -f name=kafka) kafka-topics \
  --create --topic input-documents \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1 --if-not-exists || true

docker exec -it $(docker ps -q -f name=kafka) kafka-topics \
  --create --topic chunked-documents \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1 --if-not-exists || true

docker exec -it $(docker ps -q -f name=kafka) kafka-topics \
  --create --topic processed-documents \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1 --if-not-exists || true

docker exec -it $(docker ps -q -f name=kafka) kafka-topics \
  --create --topic test-module-output \
  --bootstrap-server localhost:9092 \
  --partitions 3 --replication-factor 1 --if-not-exists || true

echo -e "${GREEN}✓ Kafka topics created${NC}"

# Create source mapping configuration
echo -e "${YELLOW}Creating source mapping configuration...${NC}"
cat > /tmp/source-mappings.json <<EOF
{
  "default": {
    "pipelineId": "simple-test-pipeline",
    "description": "Default pipeline for unmapped sources"
  },
  "test-source": {
    "pipelineId": "simple-test-pipeline",
    "description": "Test source for development"
  },
  "document-source": {
    "pipelineId": "document-processing-pipeline",
    "description": "Document processing source"
  }
}
EOF

# Create pipeline cluster configuration
cat > /tmp/dev-cluster-config.json <<EOF
{
  "clusterName": "dev-cluster",
  "pipelineGraphConfig": {
    "pipelineIds": ["simple-test-pipeline", "document-processing-pipeline", "kafka-output-pipeline"]
  },
  "allowedKafkaTopics": [
    "input-documents",
    "chunked-documents", 
    "processed-documents",
    "test-module-output"
  ],
  "allowedGrpcServices": [
    "tika-parser",
    "chunker",
    "embedder",
    "echo",
    "test-module"
  ],
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
    "embedder": {
      "moduleUrl": "dns:///embedder:50053",
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

# Create simple test pipeline
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
  }
}
EOF

# Create document processing pipeline
cat > /tmp/document-processing-pipeline.json <<EOF
{
  "id": "document-processing-pipeline",
  "name": "Document Processing Pipeline",
  "description": "Full document processing with embeddings",
  "pipelineSteps": {
    "parse": {
      "name": "Parse Document",
      "moduleId": "tika",
      "nextSteps": ["chunk"]
    },
    "chunk": {
      "name": "Chunk Text",
      "moduleId": "chunker",
      "nextSteps": ["embed"]
    },
    "embed": {
      "name": "Generate Embeddings",
      "moduleId": "embedder",
      "nextSteps": ["output"]
    },
    "output": {
      "name": "Output to Test Module",
      "moduleId": "test-module",
      "nextSteps": []
    }
  }
}
EOF

# Create Kafka output pipeline
cat > /tmp/kafka-output-pipeline.json <<EOF
{
  "id": "kafka-output-pipeline",
  "name": "Kafka Output Pipeline",
  "description": "Pipeline that outputs to Kafka via test-module",
  "pipelineSteps": {
    "parse": {
      "name": "Parse Document",
      "moduleId": "tika",
      "nextSteps": ["output"]
    },
    "output": {
      "name": "Output to Kafka",
      "moduleId": "test-module",
      "nextSteps": []
    }
  },
  "kafkaInputDefinition": {
    "topics": ["input-documents"],
    "groupId": "kafka-pipeline-consumer"
  }
}
EOF

# Store all configurations in Consul
echo -e "${YELLOW}Storing configurations in Consul...${NC}"

# Store cluster configuration
curl -X PUT http://localhost:8500/v1/kv/config/pipeline/clusters/dev-cluster \
  --data-binary @/tmp/dev-cluster-config.json

# Store source mappings
curl -X PUT http://localhost:8500/v1/kv/config/pipeline/source-mappings \
  --data-binary @/tmp/source-mappings.json

# Store pipeline configurations
curl -X PUT http://localhost:8500/v1/kv/config/pipeline/pipelines/simple-test-pipeline \
  --data-binary @/tmp/simple-test-pipeline.json

curl -X PUT http://localhost:8500/v1/kv/config/pipeline/pipelines/document-processing-pipeline \
  --data-binary @/tmp/document-processing-pipeline.json

curl -X PUT http://localhost:8500/v1/kv/config/pipeline/pipelines/kafka-output-pipeline \
  --data-binary @/tmp/kafka-output-pipeline.json

echo -e "${GREEN}✓ Configurations stored in Consul${NC}"

# Register all module services
echo -e "${YELLOW}Registering module services...${NC}"

# Register Tika Parser
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "tika-parser-1",
  "Name": "tika-parser",
  "Tags": ["grpc", "module", "document-processing"],
  "Address": "localhost",
  "Port": 50051,
  "Meta": {
    "module_type": "parser",
    "version": "1.0.0"
  },
  "Check": {
    "HTTP": "http://localhost:8081/health",
    "Interval": "10s"
  }
}'

# Register Chunker
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "chunker-1",
  "Name": "chunker",
  "Tags": ["grpc", "module", "text-processing"],
  "Address": "localhost",
  "Port": 50052,
  "Meta": {
    "module_type": "chunker",
    "version": "1.0.0"
  },
  "Check": {
    "HTTP": "http://localhost:8082/health",
    "Interval": "10s"
  }
}'

# Register Embedder
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "embedder-1",
  "Name": "embedder",
  "Tags": ["grpc", "module", "ml-processing"],
  "Address": "localhost",
  "Port": 50053,
  "Meta": {
    "module_type": "embedder",
    "version": "1.0.0"
  },
  "Check": {
    "HTTP": "http://localhost:8083/health",
    "Interval": "10s"
  }
}'

# Register Echo
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "echo-1",
  "Name": "echo",
  "Tags": ["grpc", "module", "test"],
  "Address": "localhost",
  "Port": 50054,
  "Meta": {
    "module_type": "echo",
    "version": "1.0.0"
  },
  "Check": {
    "HTTP": "http://localhost:8084/health",
    "Interval": "10s"
  }
}'

# Register Test Module
curl -X PUT http://localhost:8500/v1/agent/service/register -d '{
  "ID": "test-module-1",
  "Name": "test-module",
  "Tags": ["grpc", "module", "kafka-output", "test"],
  "Address": "localhost",
  "Port": 50062,
  "Meta": {
    "module_type": "test-output",
    "version": "1.0.0",
    "output_type": "kafka"
  },
  "Check": {
    "HTTP": "http://localhost:8085/health",
    "Interval": "10s"
  }
}'

echo -e "${GREEN}✓ Module services registered${NC}"

# Create initial test data
echo -e "${YELLOW}Creating test document...${NC}"
cat > /tmp/test-document.json <<EOF
{
  "id": "test-doc-1",
  "source": "test-source",
  "content": "This is a test document for the YAPPY pipeline. It contains some text that will be processed through Tika, chunked, and potentially embedded.",
  "metadata": {
    "created": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "type": "text/plain",
    "author": "YAPPY Test"
  }
}
EOF

echo -e "${GREEN}✓ Test document created${NC}"

echo ""
echo -e "${GREEN}✅ YAPPY cluster setup complete!${NC}"
echo ""
echo "📊 Cluster Information:"
echo "  - Cluster Name: dev-cluster"
echo "  - Pipelines: 3 configured"
echo "  - Modules: 5 registered"
echo "  - Kafka Topics: 3 created"
echo ""
echo "🔗 Access Points:"
echo "  - Consul UI: http://localhost:8500"
echo "  - Engine HTTP: http://localhost:8091 (when started)"
echo "  - Engine gRPC: localhost:50070 (when started)"
echo ""
echo "📝 Next Steps:"
echo "  1. Start the engine: ./run-engine-local.sh"
echo "  2. Send test document to engine via gRPC"
echo "  3. Monitor Kafka topics for output"
echo ""
echo "💡 Test document saved at: /tmp/test-document.json"