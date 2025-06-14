#!/bin/bash

# Script to manage Kafka topic and gRPC service whitelisting in YAPPY

echo "📋 YAPPY Whitelist Manager"
echo ""

# Colors
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

# Function to get current cluster config
get_cluster_config() {
    local cluster=$1
    curl -s http://localhost:8500/v1/kv/config/pipeline/clusters/$cluster?raw
}

# Function to update cluster config
update_cluster_config() {
    local cluster=$1
    local config=$2
    curl -X PUT http://localhost:8500/v1/kv/config/pipeline/clusters/$cluster \
        --data-binary "$config"
}

# Function to add Kafka topic to whitelist
add_kafka_topic() {
    local cluster=$1
    local topic=$2
    
    echo -e "${YELLOW}Adding Kafka topic '$topic' to cluster '$cluster'...${NC}"
    
    # Get current config
    local config=$(get_cluster_config $cluster)
    if [ -z "$config" ]; then
        echo -e "${RED}Error: Cluster '$cluster' not found in Consul${NC}"
        return 1
    fi
    
    # Parse and update JSON (using jq if available, otherwise sed)
    if command -v jq &> /dev/null; then
        # Use jq to properly update the JSON
        local updated_config=$(echo "$config" | jq --arg topic "$topic" '
            .allowedKafkaTopics = (.allowedKafkaTopics // []) + [$topic] | 
            .allowedKafkaTopics |= unique
        ')
        update_cluster_config "$cluster" "$updated_config"
    else
        echo -e "${YELLOW}Warning: jq not found. Manual editing required.${NC}"
        echo "Current topics:"
        echo "$config" | grep -o '"allowedKafkaTopics":\s*\[[^]]*\]'
        echo ""
        echo "Please add '$topic' manually to the allowedKafkaTopics array in Consul UI"
        return 1
    fi
    
    echo -e "${GREEN}✓ Topic '$topic' added to whitelist${NC}"
}

# Function to add gRPC service to whitelist
add_grpc_service() {
    local cluster=$1
    local service=$2
    
    echo -e "${YELLOW}Adding gRPC service '$service' to cluster '$cluster'...${NC}"
    
    # Get current config
    local config=$(get_cluster_config $cluster)
    if [ -z "$config" ]; then
        echo -e "${RED}Error: Cluster '$cluster' not found in Consul${NC}"
        return 1
    fi
    
    # Parse and update JSON
    if command -v jq &> /dev/null; then
        local updated_config=$(echo "$config" | jq --arg service "$service" '
            .allowedGrpcServices = (.allowedGrpcServices // []) + [$service] | 
            .allowedGrpcServices |= unique
        ')
        update_cluster_config "$cluster" "$updated_config"
    else
        echo -e "${YELLOW}Warning: jq not found. Manual editing required.${NC}"
        echo "Current services:"
        echo "$config" | grep -o '"allowedGrpcServices":\s*\[[^]]*\]'
        echo ""
        echo "Please add '$service' manually to the allowedGrpcServices array in Consul UI"
        return 1
    fi
    
    echo -e "${GREEN}✓ Service '$service' added to whitelist${NC}"
}

# Function to list current whitelists
list_whitelists() {
    local cluster=$1
    
    echo -e "${YELLOW}Fetching whitelists for cluster '$cluster'...${NC}"
    
    local config=$(get_cluster_config $cluster)
    if [ -z "$config" ]; then
        echo -e "${RED}Error: Cluster '$cluster' not found in Consul${NC}"
        return 1
    fi
    
    if command -v jq &> /dev/null; then
        echo ""
        echo -e "${GREEN}Kafka Topics Whitelist:${NC}"
        echo "$config" | jq -r '.allowedKafkaTopics[]? // empty' | sed 's/^/  - /'
        
        echo ""
        echo -e "${GREEN}gRPC Services Whitelist:${NC}"
        echo "$config" | jq -r '.allowedGrpcServices[]? // empty' | sed 's/^/  - /'
    else
        echo "$config" | grep -E '"allowed(KafkaTopics|GrpcServices)"'
    fi
}

# Main menu
if [ $# -eq 0 ]; then
    echo "Usage: $0 <command> [args]"
    echo ""
    echo "Commands:"
    echo "  list <cluster>              - List current whitelists"
    echo "  add-topic <cluster> <topic> - Add Kafka topic to whitelist"
    echo "  add-service <cluster> <svc> - Add gRPC service to whitelist"
    echo ""
    echo "Example:"
    echo "  $0 list dev-cluster"
    echo "  $0 add-topic dev-cluster my-new-topic"
    echo "  $0 add-service dev-cluster my-grpc-service"
    exit 1
fi

# Process commands
case "$1" in
    list)
        if [ -z "$2" ]; then
            echo "Error: Cluster name required"
            exit 1
        fi
        list_whitelists "$2"
        ;;
    add-topic)
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo "Error: Cluster name and topic name required"
            exit 1
        fi
        add_kafka_topic "$2" "$3"
        ;;
    add-service)
        if [ -z "$2" ] || [ -z "$3" ]; then
            echo "Error: Cluster name and service name required"
            exit 1
        fi
        add_grpc_service "$2" "$3"
        ;;
    *)
        echo "Unknown command: $1"
        exit 1
        ;;
esac