#!/bin/bash

# Monitor Kafka topics for YAPPY pipeline output

echo "📊 Monitoring Kafka topics for YAPPY pipeline..."
echo ""

# Function to consume from a topic
consume_topic() {
    local topic=$1
    local group="monitor-${topic}-$(date +%s)"
    
    echo "👁️  Monitoring topic: $topic"
    echo "Press Ctrl+C to stop..."
    echo "---"
    
    docker exec -it $(docker ps -q -f name=kafka) kafka-console-consumer \
        --bootstrap-server localhost:9092 \
        --topic "$topic" \
        --group "$group" \
        --from-beginning \
        --max-messages 10 \
        --property print.key=true \
        --property print.timestamp=true \
        --property key.separator=" | "
}

# Menu for topic selection
echo "Select topic to monitor:"
echo "1) input-documents"
echo "2) chunked-documents"
echo "3) processed-documents"
echo "4) test-module-output"
echo "5) All topics (in sequence)"
echo ""
read -p "Enter choice (1-5): " choice

case $choice in
    1)
        consume_topic "input-documents"
        ;;
    2)
        consume_topic "chunked-documents"
        ;;
    3)
        consume_topic "processed-documents"
        ;;
    4)
        consume_topic "test-module-output"
        ;;
    5)
        echo "Monitoring all topics (showing last 5 messages from each)..."
        echo ""
        for topic in input-documents chunked-documents processed-documents test-module-output; do
            echo "=== Topic: $topic ==="
            docker exec $(docker ps -q -f name=kafka) kafka-console-consumer \
                --bootstrap-server localhost:9092 \
                --topic "$topic" \
                --group "monitor-all-$(date +%s)" \
                --from-beginning \
                --max-messages 5 \
                --timeout-ms 5000 \
                --property print.key=true \
                --property print.timestamp=true \
                2>/dev/null || echo "No messages in $topic"
            echo ""
        done
        ;;
    *)
        echo "Invalid choice"
        exit 1
        ;;
esac