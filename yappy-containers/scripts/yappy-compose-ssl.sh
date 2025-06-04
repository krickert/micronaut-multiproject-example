#!/bin/bash
set -e

# YAPPY Docker Compose Control Script with SSL Support

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
COMPOSE_FILE="docker-compose.yml"
COMPOSE_FILE_SSL="docker-compose-ssl.yml"
PROJECT_NAME="yappy"
USE_SSL=false

# Check for SSL flag
if [[ "$1" == "--ssl" ]] || [[ "$2" == "--ssl" ]] || [[ "$YAPPY_USE_SSL" == "true" ]]; then
    USE_SSL=true
    COMPOSE_FILE="$COMPOSE_FILE_SSL"
    echo -e "${GREEN}Using SSL configuration${NC}"
    
    # Check if certificates exist
    if [ ! -f "./certs/server.crt" ]; then
        echo -e "${YELLOW}SSL certificates not found!${NC}"
        echo "Run ./setup-ssl.sh first to set up certificates"
        exit 1
    fi
    
    # Remove --ssl from arguments
    set -- "${@/--ssl/}"
fi

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

# Import other functions from original script
source <(sed -n '/^# Start all services/,/^# Main script logic/p' yappy-compose.sh | grep -v "^# Main script logic")

# Override show_status for SSL URLs
show_status() {
    echo -e "${BLUE}=== Service Status ===${NC}"
    $COMPOSE_CMD -f $COMPOSE_FILE -p $PROJECT_NAME ps
    
    echo ""
    echo -e "${BLUE}=== Service URLs ===${NC}"
    
    if [ "$USE_SSL" = true ]; then
        echo "Consul UI:              https://localhost:8501"
        echo "Apicurio Registry:      https://localhost:8443"
        echo "OpenSearch:             https://localhost:9200 (admin:admin)"
        echo "OpenSearch Dashboards:  https://localhost:5601"
        echo ""
        echo "Engine APIs (HTTPS):"
        echo "  Tika Parser:          https://localhost:8082"
        echo "  Chunker:              https://localhost:8083"
        echo "  Embedder:             https://localhost:8084"
        echo "  OpenSearch Sink:      https://localhost:8085"
        echo ""
        echo -e "${YELLOW}Note: Using self-signed certificates. Accept security warnings in browser.${NC}"
    else
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
    fi
    
    echo ""
    echo -e "${BLUE}=== Registered Services in Consul ===${NC}"
    
    if [ "$USE_SSL" = true ]; then
        curl -k -s https://localhost:8501/v1/catalog/services 2>/dev/null | jq -r 'keys[]' | sort || echo "Consul not ready yet"
    else
        curl -s http://localhost:8500/v1/catalog/services 2>/dev/null | jq -r 'keys[]' | sort || echo "Consul not ready yet"
    fi
}

# Show help with SSL option
show_help() {
    echo "YAPPY Docker Compose Control Script with SSL Support"
    echo ""
    echo "Usage: $0 [--ssl] [command] [options]"
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
    echo "  --ssl     Use SSL/TLS configuration"
    echo "  -f        Follow logs (with logs command)"
    echo "  -h        Show this help"
    echo ""
    echo "Examples:"
    echo "  $0 up                      # Start everything (HTTP)"
    echo "  $0 --ssl up                # Start everything (HTTPS)"
    echo "  $0 --ssl status            # Show status with SSL URLs"
    echo "  $0 logs -f engine-tika     # Follow logs for engine-tika"
    echo ""
    echo "SSL Setup:"
    echo "  ./setup-ssl.sh             # Set up SSL certificates"
    echo "  export YAPPY_USE_SSL=true  # Always use SSL"
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