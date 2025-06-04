#!/bin/bash
set -e

# YAPPY Docker Compose Control Script

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.yml"
PROJECT_NAME="yappy"

# Help function
show_help() {
    echo "YAPPY Docker Compose Control Script"
    echo ""
    echo "Usage: $0 [command] [options]"
    echo ""
    echo "Commands:"
    echo "  up        Start all services (infrastructure + engines)"
    echo "  down      Stop and remove all services"
    echo "  start     Start stopped services"
    echo "  stop      Stop running services"
    echo "  restart   Restart all services"
    echo "  status    Show service status"
    echo "  logs      Show logs (use -f to follow)"
    echo "  infra     Start only infrastructure services"
    echo "  engines   Start only engine services (requires infra)"
    echo "  test      Start with test connector enabled"
    echo "  clean     Stop services and remove volumes (CAUTION: deletes data)"
    echo ""
    echo "Options:"
    echo "  -f        Follow logs (with logs command)"
    echo "  -h        Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 up                  # Start everything"
    echo "  $0 infra               # Start only infrastructure"
    echo "  $0 logs -f engine-tika # Follow logs for engine-tika"
    echo "  $0 test                # Start with test connector"
}

# Check if docker-compose is available
check_docker_compose() {
    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null 2>&1; then
        echo -e "${RED}Error: docker-compose is not installed${NC}"
        exit 1
    fi
    
    # Determine which command to use
    if docker compose version &> /dev/null 2>&1; then
        COMPOSE_CMD="docker compose"
    else
        COMPOSE_CMD="docker-compose"
    fi
}

# Start all services
start_all() {
    echo -e "${BLUE}Starting YAPPY services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME up -d
    echo ""
    wait_for_health
    show_status
}

# Start infrastructure only
start_infra() {
    echo -e "${BLUE}Starting infrastructure services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME up -d \
        consul kafka apicurio opensearch opensearch-dashboards
    echo ""
    echo "Waiting for infrastructure to be ready..."
    sleep 10
    show_status
}

# Start engines only
start_engines() {
    echo -e "${BLUE}Starting engine services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME up -d \
        engine-tika engine-chunker engine-embedder engine-opensearch-sink
    echo ""
    wait_for_health
    show_status
}

# Start with test profile
start_test() {
    echo -e "${BLUE}Starting YAPPY with test connector...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME --profile test up -d
    echo ""
    wait_for_health
    show_status
}

# Wait for services to be healthy
wait_for_health() {
    echo "Waiting for services to be healthy..."
    
    # Wait for critical services
    local services=("consul" "kafka" "apicurio")
    local max_attempts=30
    
    for service in "${services[@]}"; do
        echo -n "  Waiting for $service..."
        local attempt=1
        while [ $attempt -le $max_attempts ]; do
            if $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME ps $service 2>/dev/null | grep -q "healthy"; then
                echo -e " ${GREEN}✓${NC}"
                break
            fi
            echo -n "."
            sleep 2
            ((attempt++))
        done
        if [ $attempt -gt $max_attempts ]; then
            echo -e " ${RED}✗ Timeout${NC}"
        fi
    done
    
    echo ""
    echo "Giving engines time to register with Consul..."
    sleep 10
}

# Show service status
show_status() {
    echo -e "${BLUE}=== Service Status ===${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME ps
    
    echo ""
    echo -e "${BLUE}=== Service URLs ===${NC}"
    echo "Consul UI:              http://localhost:8500"
    echo "Apicurio Registry:      http://localhost:8081"
    echo "OpenSearch:             http://localhost:9200"
    echo "OpenSearch Dashboards:  http://localhost:5601"
    echo ""
    echo "Engine APIs:"
    echo "  Tika Parser:          http://localhost:8082"
    echo "  Chunker:              http://localhost:8083"
    echo "  Embedder:             http://localhost:8084"
    echo "  OpenSearch Sink:      http://localhost:8085"
    echo ""
    echo -e "${BLUE}=== Registered Services in Consul ===${NC}"
    curl -s http://localhost:8500/v1/catalog/services 2>/dev/null | jq -r 'keys[]' | sort || echo "Consul not ready yet"
}

# Show logs
show_logs() {
    local follow=""
    local service=""
    
    # Parse arguments
    while [[ $# -gt 0 ]]; do
        case $1 in
            -f) follow="-f" ;;
            *) service="$1" ;;
        esac
        shift
    done
    
    if [ -n "$service" ]; then
        $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME logs $follow $service
    else
        $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME logs $follow
    fi
}

# Stop services
stop_services() {
    echo -e "${BLUE}Stopping YAPPY services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME stop
}

# Remove services
down_services() {
    echo -e "${BLUE}Stopping and removing YAPPY services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME down
}

# Clean everything including volumes
clean_all() {
    echo -e "${YELLOW}WARNING: This will delete all data!${NC}"
    read -p "Are you sure? (y/N) " -n 1 -r
    echo
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        echo -e "${RED}Cleaning up everything...${NC}"
        $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME down -v --remove-orphans
        echo "Cleanup complete"
    else
        echo "Cancelled"
    fi
}

# Restart services
restart_services() {
    echo -e "${BLUE}Restarting YAPPY services...${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME restart
    wait_for_health
    show_status
}

# Main script logic
check_docker_compose

case "${1:-help}" in
    up)
        start_all
        ;;
    down)
        down_services
        ;;
    start)
        $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME start
        ;;
    stop)
        stop_services
        ;;
    restart)
        restart_services
        ;;
    status)
        show_status
        ;;
    logs)
        shift
        show_logs "$@"
        ;;
    infra)
        start_infra
        ;;
    engines)
        start_engines
        ;;
    test)
        start_test
        ;;
    clean)
        clean_all
        ;;
    -h|--help|help)
        show_help
        ;;
    *)
        echo -e "${RED}Unknown command: $1${NC}"
        echo ""
        show_help
        exit 1
        ;;
esac