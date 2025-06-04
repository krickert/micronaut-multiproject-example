#!/bin/bash
set -e

# Setup document processing pipeline via Admin API
echo "=== Setting up YAPPY Document Processing Pipeline ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Base URL
ENGINE_URL="http://localhost:8082"

# Step 1: Check current pipelines
echo -e "${BLUE}Current pipelines:${NC}"
curl -s "$ENGINE_URL/api/admin/pipelines" | jq .

# Step 2: Create document processing pipeline
echo ""
echo -e "${BLUE}Creating document processing pipeline...${NC}"

cat > pipeline-config.json << 'EOF'
{
  "id": "document-processing",
  "name": "Document Processing Pipeline",
  "description": "Complete document processing pipeline with Tika, Chunker, Embedder, and OpenSearch",
  "steps": [
    {
      "id": "tika-parser",
      "name": "Tika Document Parser",
      "type": "MODULE",
      "moduleType": "tika-parser",
      "processorInfo": {
        "serviceName": "tika-parser",
        "methodName": "parse"
      },
      "config": {
        "maxDocumentSize": "10485760",
        "extractMetadata": "true",
        "detectLanguage": "true"
      },
      "inputConfig": {
        "kafkaTopic": "document-input"
      },
      "outputTargets": [
        {
          "stepId": "text-chunker",
          "transportType": "GRPC"
        }
      ]
    },
    {
      "id": "text-chunker",
      "name": "Text Chunker",
      "type": "MODULE",
      "moduleType": "chunker",
      "processorInfo": {
        "serviceName": "chunker",
        "methodName": "chunk"
      },
      "config": {
        "chunkSize": "512",
        "chunkOverlap": "50",
        "minChunkSize": "100"
      },
      "dependencies": ["tika-parser"],
      "outputTargets": [
        {
          "stepId": "text-embedder",
          "transportType": "GRPC"
        }
      ]
    },
    {
      "id": "text-embedder",
      "name": "Text Embedder",
      "type": "MODULE",
      "moduleType": "embedder",
      "processorInfo": {
        "serviceName": "embedder",
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
          "transportType": "GRPC"
        }
      ]
    },
    {
      "id": "opensearch-indexer",
      "name": "OpenSearch Indexer",
      "type": "MODULE",
      "moduleType": "opensearch-sink",
      "processorInfo": {
        "serviceName": "opensearch-sink",
        "methodName": "index"
      },
      "config": {
        "indexName": "yappy-documents",
        "indexType": "_doc",
        "batchSize": "100",
        "flushInterval": "5000"
      },
      "dependencies": ["text-embedder"]
    }
  ]
}
EOF

# Create the pipeline
RESPONSE=$(curl -s -X POST \
  -H "Content-Type: application/json" \
  -d @pipeline-config.json \
  "$ENGINE_URL/api/admin/pipelines" || echo '{"error": "Failed to create pipeline"}')

echo "$RESPONSE" | jq .

if echo "$RESPONSE" | grep -q "error"; then
    echo -e "${RED}Failed to create pipeline${NC}"
    exit 1
fi

echo -e "${GREEN}✓ Pipeline created successfully${NC}"

# Step 3: Create necessary Kafka topics
echo ""
echo -e "${BLUE}Creating Kafka topics...${NC}"

# Create input topic
docker exec kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create --topic document-input \
    --partitions 3 --replication-factor 1 \
    --config retention.ms=604800000 2>/dev/null && \
    echo -e "${GREEN}✓ Created document-input topic${NC}" || \
    echo -e "${YELLOW}⚠ Topic document-input already exists${NC}"

# Create intermediate topics
for topic in parsed-documents text-chunks embedded-documents; do
    docker exec kafka /opt/kafka/bin/kafka-topics.sh \
        --bootstrap-server localhost:9092 \
        --create --topic $topic \
        --partitions 3 --replication-factor 1 2>/dev/null && \
        echo -e "${GREEN}✓ Created $topic topic${NC}" || \
        echo -e "${YELLOW}⚠ Topic $topic already exists${NC}"
done

# Step 4: Create OpenSearch index
echo ""
echo -e "${BLUE}Creating OpenSearch index...${NC}"

curl -s -X PUT "http://localhost:9200/yappy-documents" \
    -H "Content-Type: application/json" \
    -d '{
        "settings": {
            "number_of_shards": 2,
            "number_of_replicas": 0,
            "analysis": {
                "analyzer": {
                    "default": {
                        "type": "standard"
                    }
                }
            }
        },
        "mappings": {
            "properties": {
                "id": {"type": "keyword"},
                "content": {
                    "type": "text",
                    "analyzer": "standard"
                },
                "title": {"type": "text"},
                "source": {"type": "keyword"},
                "timestamp": {"type": "date"},
                "chunk_id": {"type": "keyword"},
                "chunk_index": {"type": "integer"},
                "embedding": {
                    "type": "dense_vector",
                    "dims": 384,
                    "index": true,
                    "similarity": "cosine"
                },
                "metadata": {
                    "type": "object",
                    "enabled": true
                }
            }
        }
    }' | jq . && echo -e "${GREEN}✓ OpenSearch index created${NC}" || echo -e "${YELLOW}⚠ Index may already exist${NC}"

# Step 5: Activate the pipeline
echo ""
echo -e "${BLUE}Activating pipeline...${NC}"

curl -s -X PUT "$ENGINE_URL/api/admin/pipelines/document-processing/activate" | jq .

# Step 6: Check pipeline status
echo ""
echo -e "${BLUE}Pipeline Status:${NC}"
curl -s "$ENGINE_URL/api/admin/pipelines/document-processing" | jq .

# Step 7: List all topics
echo ""
echo -e "${BLUE}Kafka Topics:${NC}"
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list

echo ""
echo -e "${GREEN}=== Pipeline Setup Complete! ===${NC}"
echo ""
echo "The document processing pipeline is now configured with:"
echo "  1. Tika Parser - Extracts text from documents"
echo "  2. Text Chunker - Splits text into semantic chunks"
echo "  3. Text Embedder - Creates vector embeddings"
echo "  4. OpenSearch Sink - Indexes documents for search"
echo ""
echo "To test the pipeline:"
echo "  ./test-document-submission.sh"
echo ""
echo "To monitor:"
echo "  - Consul UI: http://localhost:8500"
echo "  - OpenSearch: http://localhost:9200/yappy-documents/_search"
echo "  - Kafka topics: docker exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic document-input --from-beginning"

# Cleanup
rm -f pipeline-config.json