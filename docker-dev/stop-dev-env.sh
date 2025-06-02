#!/bin/bash

# Stop Docker development environment for Yappy

echo "Stopping Yappy development environment..."
cd "$(dirname "$0")" || exit 1

# Check if Docker is running
if ! docker info &> /dev/null; then
    echo "Error: Docker is not running."
    exit 1
fi

# Stop and remove containers
echo "Stopping Docker containers..."
docker-compose down

# Ask if user wants to remove volumes
read -p "Do you want to remove data volumes as well? (y/n): " REMOVE_VOLUMES
if [ "$REMOVE_VOLUMES" = "y" ] || [ "$REMOVE_VOLUMES" = "Y" ]; then
    echo "Removing volumes..."
    docker-compose down -v
    echo "Volumes removed."
fi

echo "Development environment stopped."