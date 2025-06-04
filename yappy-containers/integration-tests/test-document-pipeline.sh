#!/bin/bash
set -e

# End-to-end document processing pipeline test
echo "=== YAPPY Document Processing Pipeline Test ==="
echo ""

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Test configuration
TEST_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TEST_DOC="$TEST_DIR/test-document.txt"

# Create test document
echo "Creating test document..."
cat > "$TEST_DOC" << 'EOF'
The YAPPY System Architecture

YAPPY (Yet Another Processing Pipeline sYstem) is designed to handle document processing 
at scale using a microservices architecture. The system consists of several key components:

1. Document Ingestion: The system accepts documents through various connectors including 
   S3, web crawlers, and direct file uploads. Each document is assigned a unique identifier 
   and tracked throughout the processing pipeline.

2. Text Extraction: The Tika parser module extracts text content from various document 
   formats including PDF, Word, HTML, and plain text. It preserves metadata and structure 
   information for downstream processing.

3. Text Chunking: Large documents are split into smaller, semantically meaningful chunks 
   using the chunker module. This ensures that each piece of text is of optimal size for 
   embedding and retrieval operations.

4. Embedding Generation: The embedder module converts text chunks into high-dimensional 
   vectors using state-of-the-art language models. These embeddings capture the semantic 
   meaning of the text and enable similarity search.

5. Search Index: Processed documents and their embeddings are stored in OpenSearch, 
   enabling fast full-text and semantic search capabilities. The index supports complex 
   queries and aggregations.

This modular architecture allows for easy scaling and customization of the processing 
pipeline to meet specific requirements.
EOF

# Function to create pipeline configuration
create_pipeline_config() {
    echo "Creating pipeline configuration..."
    
    # Create a simple pipeline configuration
    cat > "$TEST_DIR/test-pipeline.json" << 'EOF'
{
  "id": "test-document-pipeline",
  "name": "Test Document Processing Pipeline",
  "description": "End-to-end test pipeline",
  "steps": [
    {
      "id": "parse-step",
      "name": "Tika Parser",
      "type": "MODULE",
      "moduleType": "tika-parser",
      "config": {
        "maxDocumentSize": "10MB",
        "extractMetadata": true
      }
    },
    {
      "id": "chunk-step",
      "name": "Text Chunker",
      "type": "MODULE",
      "moduleType": "chunker",
      "config": {
        "chunkSize": 512,
        "chunkOverlap": 50
      },
      "dependencies": ["parse-step"]
    },
    {
      "id": "embed-step",
      "name": "Text Embedder",
      "type": "MODULE",
      "moduleType": "embedder",
      "config": {
        "model": "all-MiniLM-L6-v2",
        "batchSize": 16
      },
      "dependencies": ["chunk-step"]
    },
    {
      "id": "index-step",
      "name": "OpenSearch Indexer",
      "type": "MODULE",
      "moduleType": "opensearch-sink",
      "config": {
        "indexName": "test-documents",
        "indexSettings": {
          "number_of_shards": 1,
          "number_of_replicas": 0
        }
      },
      "dependencies": ["embed-step"]
    }
  ]
}
EOF
}

# Function to wait for pipeline readiness
wait_for_pipeline() {
    echo -n "Waiting for pipeline components to be ready..."
    local max_attempts=30
    local attempt=1
    
    while [ $attempt -le $max_attempts ]; do
        # Check if all engines are healthy
        if curl -s http://localhost:8082/health | grep -q UP && \
           curl -s http://localhost:8083/health | grep -q UP && \
           curl -s http://localhost:8084/health | grep -q UP && \
           curl -s http://localhost:8085/health | grep -q UP; then
            echo -e " ${GREEN}✓${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        ((attempt++))
    done
    echo -e " ${RED}✗ Timeout${NC}"
    return 1
}

# Function to submit document
submit_document() {
    echo "Submitting test document to pipeline..."
    
    # Create document submission payload
    cat > "$TEST_DIR/submit-request.json" << EOF
{
  "documentId": "test-doc-$(date +%s)",
  "source": "integration-test",
  "contentType": "text/plain",
  "content": "$(base64 < "$TEST_DOC" | tr -d '\n')",
  "metadata": {
    "title": "YAPPY System Architecture",
    "author": "Integration Test",
    "timestamp": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  }
}
EOF

    # Submit to the first engine (Tika parser)
    RESPONSE=$(curl -s -X POST \
        -H "Content-Type: application/json" \
        -d @"$TEST_DIR/submit-request.json" \
        http://localhost:8082/api/v1/documents/submit)
    
    echo "Submission response: $RESPONSE"
    DOCUMENT_ID=$(echo "$RESPONSE" | jq -r '.documentId // empty')
    
    if [ -z "$DOCUMENT_ID" ]; then
        echo -e "${RED}Failed to submit document${NC}"
        return 1
    fi
    
    echo -e "${GREEN}Document submitted successfully: $DOCUMENT_ID${NC}"
    return 0
}

# Function to check processing status
check_processing_status() {
    local document_id=$1
    local max_attempts=60  # 2 minutes
    local attempt=1
    
    echo -n "Checking document processing status"
    while [ $attempt -le $max_attempts ]; do
        # Check if document exists in OpenSearch
        if curl -s "http://localhost:9200/test-documents/_search?q=documentId:$document_id" | grep -q "hits"; then
            echo -e " ${GREEN}✓ Document indexed${NC}"
            return 0
        fi
        echo -n "."
        sleep 2
        ((attempt++))
    done
    echo -e " ${RED}✗ Timeout${NC}"
    return 1
}

# Function to verify pipeline results
verify_results() {
    echo ""
    echo -e "${BLUE}=== Verifying Pipeline Results ===${NC}"
    
    # Check OpenSearch index
    echo "Checking OpenSearch index..."
    local index_stats=$(curl -s http://localhost:9200/test-documents/_stats)
    local doc_count=$(echo "$index_stats" | jq -r '._all.primaries.docs.count // 0')
    
    if [ "$doc_count" -gt 0 ]; then
        echo -e "  Documents indexed: ${GREEN}$doc_count${NC}"
        
        # Show sample document
        echo ""
        echo "Sample indexed document:"
        curl -s "http://localhost:9200/test-documents/_search?size=1" | jq '.hits.hits[0]._source' | head -20
    else
        echo -e "  ${RED}No documents indexed${NC}"
        return 1
    fi
    
    # Check Consul for pipeline metrics
    echo ""
    echo "Pipeline metrics in Consul:"
    curl -s http://localhost:8500/v1/kv/yappy/metrics?recurse | jq -r '.[] | .Key' | grep -E "(processed|failed|latency)" || echo "  No metrics found"
    
    return 0
}

# Main test execution
main() {
    # Check if test environment is running
    if ! docker ps | grep -q "consul-test"; then
        echo -e "${RED}Error: Test environment not running${NC}"
        echo "Please run: ./run-integration-tests.sh --keep"
        exit 1
    fi
    
    # Wait for pipeline readiness
    wait_for_pipeline || exit 1
    
    # Create pipeline configuration
    create_pipeline_config
    
    # TODO: Submit pipeline configuration to engines
    # This would normally be done through the admin API
    # For now, we'll test with default pipeline
    
    # Submit test document
    submit_document || exit 1
    
    # Wait for processing
    if [ -n "$DOCUMENT_ID" ]; then
        check_processing_status "$DOCUMENT_ID"
    fi
    
    # Verify results
    verify_results
    
    echo ""
    echo -e "${BLUE}=== Test Complete ===${NC}"
    echo "To view detailed logs:"
    echo "  docker logs engine-tika-test"
    echo "  docker logs engine-chunker-test"
    echo "  docker logs engine-embedder-test"
    echo "  docker logs engine-opensearch-sink-test"
}

# Run main test
main