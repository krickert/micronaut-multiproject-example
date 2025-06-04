#!/bin/bash

# YAPPY Administration Menu
# This script provides a menu interface for common YAPPY operations

# Colors
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Display header
display_header() {
    clear
    echo -e "${BLUE}===================================${NC}"
    echo -e "${BLUE}     YAPPY Administration Menu     ${NC}"
    echo -e "${BLUE}===================================${NC}"
    echo ""
}

# Display menu
display_menu() {
    echo -e "${GREEN}Docker Compose Operations:${NC}"
    echo "  1) Start YAPPY (standard)"
    echo "  2) Start YAPPY (with SSL)"
    echo "  3) Stop YAPPY"
    echo "  4) View logs"
    echo ""
    echo -e "${GREEN}Setup & Configuration:${NC}"
    echo "  5) Setup SSL certificates"
    echo "  6) Setup document pipeline"
    echo "  7) Configure pipeline via API"
    echo ""
    echo -e "${GREEN}Testing:${NC}"
    echo "  8) Test system connectivity"
    echo "  9) Test document processing"
    echo "  10) Test basic Kafka flow"
    echo ""
    echo -e "${GREEN}Build & Deploy:${NC}"
    echo "  11) Build all containers"
    echo "  12) Push images to registry"
    echo ""
    echo -e "${GREEN}Monitoring:${NC}"
    echo "  13) Open Consul UI"
    echo "  14) Open OpenSearch"
    echo "  15) View Kafka consumers"
    echo ""
    echo "  q) Quit"
    echo ""
}

# Execute choice
execute_choice() {
    case $1 in
        1)
            echo -e "${BLUE}Starting YAPPY...${NC}"
            "$SCRIPT_DIR/scripts/yappy-compose.sh" up
            ;;
        2)
            echo -e "${BLUE}Starting YAPPY with SSL...${NC}"
            "$SCRIPT_DIR/scripts/yappy-compose-ssl.sh" up
            ;;
        3)
            echo -e "${BLUE}Stopping YAPPY...${NC}"
            "$SCRIPT_DIR/scripts/yappy-compose.sh" down
            ;;
        4)
            echo -e "${BLUE}Viewing logs (Ctrl+C to exit)...${NC}"
            docker compose logs -f
            ;;
        5)
            echo -e "${BLUE}Setting up SSL certificates...${NC}"
            "$SCRIPT_DIR/scripts/setup/setup-ssl.sh"
            ;;
        6)
            echo -e "${BLUE}Setting up document pipeline...${NC}"
            "$SCRIPT_DIR/scripts/setup/setup-document-pipeline.sh"
            ;;
        7)
            echo -e "${BLUE}Configuring pipeline via API...${NC}"
            "$SCRIPT_DIR/scripts/setup/setup-pipeline-api.sh"
            ;;
        8)
            echo -e "${BLUE}Testing system connectivity...${NC}"
            "$SCRIPT_DIR/scripts/test/test-live-system.sh"
            ;;
        9)
            echo -e "${BLUE}Testing document processing...${NC}"
            "$SCRIPT_DIR/scripts/test/test-document-processing.sh"
            ;;
        10)
            echo -e "${BLUE}Testing basic Kafka flow...${NC}"
            "$SCRIPT_DIR/scripts/test/test-basic-flow.sh"
            ;;
        11)
            echo -e "${BLUE}Building all containers...${NC}"
            "$SCRIPT_DIR/scripts/build/build-all-containers.sh"
            ;;
        12)
            echo -e "${BLUE}Pushing images to registry...${NC}"
            "$SCRIPT_DIR/scripts/build/push-images.sh"
            ;;
        13)
            echo -e "${BLUE}Opening Consul UI...${NC}"
            open http://localhost:8500 2>/dev/null || xdg-open http://localhost:8500 2>/dev/null || echo "Please open http://localhost:8500 in your browser"
            ;;
        14)
            echo -e "${BLUE}Opening OpenSearch...${NC}"
            open http://localhost:9200 2>/dev/null || xdg-open http://localhost:9200 2>/dev/null || echo "Please open http://localhost:9200 in your browser"
            ;;
        15)
            echo -e "${BLUE}Viewing Kafka consumers...${NC}"
            curl -s http://localhost:8082/api/kafka/consumers | jq .
            ;;
        q|Q)
            echo -e "${GREEN}Goodbye!${NC}"
            exit 0
            ;;
        *)
            echo -e "${RED}Invalid option. Please try again.${NC}"
            ;;
    esac
    
    if [ "$1" != "q" ] && [ "$1" != "Q" ]; then
        echo ""
        echo -e "${YELLOW}Press Enter to continue...${NC}"
        read -r
    fi
}

# Main loop
main() {
    while true; do
        display_header
        display_menu
        
        echo -n "Enter your choice: "
        read -r choice
        
        execute_choice "$choice"
    done
}

# Check if scripts directory exists
if [ ! -d "$SCRIPT_DIR/scripts" ]; then
    echo -e "${RED}Error: scripts directory not found!${NC}"
    echo "Please run this script from the yappy-containers directory."
    exit 1
fi

# Run main menu
main