#!/bin/bash
set -e

# Setup document processing pipeline using Admin APIs
echo "=== YAPPY Pipeline Setup via Admin APIs ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Base URLs for each engine
TIKA_URL="http://localhost:8082"
CHUNKER_URL="http://localhost:8083"
EMBEDDER_URL="http://localhost:8084"
OPENSEARCH_URL="http://localhost:8085"

# Step 1: Check cluster configuration
echo -e "${BLUE}1. Checking cluster configuration...${NC}"
CLUSTER_INFO=$(curl -s "$TIKA_URL/api/admin/pipelines")
echo "$CLUSTER_INFO" | jq .

# Step 2: Create pipeline with empty steps first
echo ""
echo -e "${BLUE}2. Creating document processing pipeline...${NC}"

PIPELINE_REQUEST='{
  "pipelineName": "document-processing",
  "pipelineSteps": {},
  "setAsDefault": true
}'

RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d "$PIPELINE_REQUEST" \
  "$TIKA_URL/api/admin/pipelines")

echo "$RESPONSE" | jq .

if echo "$RESPONSE" | grep -q "\"success\":true"; then
    echo -e "${GREEN}✓ Pipeline created successfully${NC}"
else
    echo -e "${YELLOW}Pipeline creation response received${NC}"
fi

# Step 3: Add pipeline steps
echo ""
echo -e "${BLUE}3. Adding pipeline steps...${NC}"

# Create the pipeline steps configuration
cat > pipeline-steps.json << 'EOF'
{
  "pipelineSteps": {
    "tika-parser": {
      "id": "tika-parser",
      "name": "Document Parser",
      "type": "MODULE",
      "moduleType": "tika-parser",
      "processorInfo": {
        "serviceName": "tika-parser",
        "servicePort": 50052,
        "methodName": "parse"
      },
      "config": {
        "maxDocumentSize": "10485760",
        "extractMetadata": "true"
      },
      "inputConfig": {
        "kafkaInput": {
          "topics": ["document-input"],
          "consumerGroup": "tika-consumer-group"
        }
      },
      "outputTargets": [
        {
          "stepId": "text-chunker",
          "transportConfig": {
            "transportType": "GRPC",
            "grpcConfig": {
              "serviceName": "chunker",
              "servicePort": 50053,
              "methodName": "chunk"
            }
          }
        }
      ]
    },
    "text-chunker": {
      "id": "text-chunker",
      "name": "Text Chunker",
      "type": "MODULE",
      "moduleType": "chunker",
      "processorInfo": {
        "serviceName": "chunker",
        "servicePort": 50053,
        "methodName": "chunk"
      },
      "config": {
        "chunkSize": "512",
        "chunkOverlap": "50"
      },
      "dependencies": ["tika-parser"],
      "outputTargets": [
        {
          "stepId": "text-embedder",
          "transportConfig": {
            "transportType": "GRPC",
            "grpcConfig": {
              "serviceName": "embedder",
              "servicePort": 50054,
              "methodName": "embed"
            }
          }
        }
      ]
    },
    "text-embedder": {
      "id": "text-embedder",
      "name": "Text Embedder",
      "type": "MODULE",
      "moduleType": "embedder",
      "processorInfo": {
        "serviceName": "embedder",
        "servicePort": 50054,
        "methodName": "embed"
      },
      "config": {
        "model": "all-MiniLM-L6-v2",
        "batchSize": "16"
      },
      "dependencies": ["text-chunker"],
      "outputTargets": [
        {
          "stepId": "opensearch-indexer",
          "transportConfig": {
            "transportType": "GRPC",
            "grpcConfig": {
              "serviceName": "opensearch-sink",
              "servicePort": 50055,
              "methodName": "index"
            }
          }
        }
      ]
    },
    "opensearch-indexer": {
      "id": "opensearch-indexer",
      "name": "OpenSearch Indexer",
      "type": "MODULE",
      "moduleType": "opensearch-sink",
      "processorInfo": {
        "serviceName": "opensearch-sink",
        "servicePort": 50055,
        "methodName": "index"
      },
      "config": {
        "indexName": "yappy-documents",
        "batchSize": "100"
      },
      "dependencies": ["text-embedder"]
    }
  }
}
EOF

# Update the pipeline with steps
UPDATE_RESPONSE=$(curl -s -X PUT \
  -H "Content-Type: application/json" \
  -d @pipeline-steps.json \
  "$TIKA_URL/api/admin/pipelines/document-processing")

echo "$UPDATE_RESPONSE" | jq .

# Step 4: Create Kafka topics via admin API
echo ""
echo -e "${BLUE}4. Creating Kafka topics...${NC}"

# Check if there's a Kafka admin endpoint
KAFKA_TOPICS=$(curl -s "$TIKA_URL/api/kafka/topics" 2>/dev/null || echo "[]")
if [ "$KAFKA_TOPICS" != "[]" ]; then
    echo "Available Kafka admin endpoints"
else
    echo "Creating topics via Docker..."
    docker exec kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create --topic document-input \
        --partitions 3 --replication-factor 1 2>/dev/null && \
        echo -e "${GREEN}✓ Created document-input topic${NC}" || \
        echo -e "${YELLOW}⚠ Topic already exists${NC}"
fi

# Step 5: Configure modules via their admin endpoints
echo ""
echo -e "${BLUE}5. Checking module configurations...${NC}"

# Check each module's admin status
for engine in "$TIKA_URL" "$CHUNKER_URL" "$EMBEDDER_URL" "$OPENSEARCH_URL"; do
    echo -n "Engine at $engine: "
    STATUS=$(curl -s "$engine/api/admin/status" 2>/dev/null | jq -r '.status // "Not available"' || echo "No admin endpoint")
    echo "$STATUS"
done

# Step 6: Create OpenSearch index
echo ""
echo -e "${BLUE}6. Creating OpenSearch index...${NC}"

INDEX_CONFIG='{
  "settings": {
    "number_of_shards": 2,
    "number_of_replicas": 0
  },
  "mappings": {
    "properties": {
      "id": {"type": "keyword"},
      "content": {"type": "text"},
      "title": {"type": "text"},
      "embedding": {
        "type": "dense_vector",
        "dims": 384,
        "index": true,
        "similarity": "cosine"
      },
      "metadata": {"type": "object"}
    }
  }
}'

curl -s -X PUT "http://localhost:9200/yappy-documents" \
    -H "Content-Type: application/json" \
    -d "$INDEX_CONFIG" | jq . && \
    echo -e "${GREEN}✓ Index created${NC}" || \
    echo -e "${YELLOW}⚠ Index may already exist${NC}"

# Step 7: Verify pipeline configuration
echo ""
echo -e "${BLUE}7. Final pipeline configuration:${NC}"
curl -s "$TIKA_URL/api/admin/pipelines/document-processing" | jq .

# Step 8: Check Kafka consumers
echo ""
echo -e "${BLUE}8. Active Kafka consumers:${NC}"
curl -s "$TIKA_URL/api/kafka/consumers" | jq .

echo ""
echo -e "${GREEN}=== Pipeline Setup Complete! ===${NC}"
echo ""
echo "Pipeline 'document-processing' is configured with:"
echo "  1. Tika Parser (port 50052) - Parses documents from Kafka"
echo "  2. Text Chunker (port 50053) - Chunks text via gRPC"
echo "  3. Text Embedder (port 50054) - Creates embeddings via gRPC"  
echo "  4. OpenSearch Sink (port 50055) - Indexes to OpenSearch"
echo ""
echo "Admin APIs available:"
echo "  - Pipeline management: http://localhost:8082/api/admin/pipelines"
echo "  - Kafka monitoring: http://localhost:8082/api/kafka/consumers"
echo "  - Module status: http://localhost:808[2-5]/api/admin/status"
echo ""
echo "To submit a document:"
echo "  ./submit-test-document.sh"

# Cleanup
rm -f pipeline-steps.json