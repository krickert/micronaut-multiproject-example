#!/bin/bash

# Script to send test documents to YAPPY engine via gRPC

echo "📤 Sending test document to YAPPY engine..."

# Check if grpcurl is installed
if ! command -v grpcurl &> /dev/null; then
    echo "❌ grpcurl not found. Installing..."
    # For Linux
    curl -L https://github.com/fullstorydev/grpcurl/releases/download/v1.8.9/grpcurl_1.8.9_linux_x86_64.tar.gz | tar xz
    sudo mv grpcurl /usr/local/bin/
    echo "✅ grpcurl installed"
fi

# Create a test PipeStream protobuf message
cat > /tmp/test-pipestream.json <<EOF
{
  "id": "$(uuidgen || echo 'test-id-1')",
  "sourceId": "test-source",
  "chunkId": 0,
  "errorStack": [],
  "metadata": {
    "docId": "doc-$(date +%s)",
    "created": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
    "contentType": "text/plain",
    "author": "YAPPY Test Client"
  },
  "lastProcessedModuleId": "",
  "pipelineStepResults": {},
  "documents": [
    {
      "originalIndexNumber": 0,
      "originalType": "TEXT_PLAIN",
      "text": "This is a test document sent via gRPC. It contains sample text that will be processed through the YAPPY pipeline. The pipeline will parse this document, chunk it into smaller pieces, and potentially generate embeddings.",
      "name": "test-document.txt"
    }
  ]
}
EOF

# Send the document using grpcurl
echo "Sending to engine at localhost:50070..."
grpcurl -plaintext \
  -d @/tmp/test-pipestream.json \
  localhost:50070 \
  com.krickert.search.engine.grpc.PipeStreamEngineService/processPipeAsync

if [ $? -eq 0 ]; then
    echo "✅ Document sent successfully!"
    echo ""
    echo "📊 Monitor output:"
    echo "  - Check Kafka topics for processed messages"
    echo "  - View Consul UI for pipeline status"
    echo "  - Check engine logs for processing details"
else
    echo "❌ Failed to send document. Make sure:"
    echo "  - Engine is running (./run-engine-local.sh)"
    echo "  - gRPC port 50070 is accessible"
    echo "  - All required services are up"
fi