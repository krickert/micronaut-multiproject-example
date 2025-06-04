#!/bin/bash

# Registry configuration script
# Source this file to set registry configuration: source ./registry-config.sh <profile>

PROFILE=${1:-local}

case $PROFILE in
    local|nas)
        export DOCKER_REGISTRY=nas:5000
        export DOCKER_NAMESPACE=yappy
        echo "Configured for local NAS registry: $DOCKER_REGISTRY/$DOCKER_NAMESPACE"
        ;;
    gitlab)
        export DOCKER_REGISTRY=registry.gitlab.com
        export DOCKER_NAMESPACE=your-gitlab-username/yappy
        echo "Configured for GitLab registry: $DOCKER_REGISTRY/$DOCKER_NAMESPACE"
        echo "NOTE: Update DOCKER_NAMESPACE with your actual GitLab username/group"
        ;;
    github)
        export DOCKER_REGISTRY=ghcr.io
        export DOCKER_NAMESPACE=your-github-username/yappy
        echo "Configured for GitHub Container Registry: $DOCKER_REGISTRY/$DOCKER_NAMESPACE"
        echo "NOTE: Update DOCKER_NAMESPACE with your actual GitHub username"
        ;;
    dockerhub)
        export DOCKER_REGISTRY=docker.io
        export DOCKER_NAMESPACE=your-dockerhub-username
        echo "Configured for Docker Hub: $DOCKER_REGISTRY/$DOCKER_NAMESPACE"
        echo "NOTE: Update DOCKER_NAMESPACE with your actual Docker Hub username"
        ;;
    *)
        echo "Unknown profile: $PROFILE"
        echo "Available profiles: local, gitlab, github, dockerhub"
        return 1
        ;;
esac

# Show current configuration
echo ""
echo "Current registry configuration:"
echo "  DOCKER_REGISTRY=$DOCKER_REGISTRY"
echo "  DOCKER_NAMESPACE=$DOCKER_NAMESPACE"
echo ""
echo "To use: source ./registry-config.sh <profile>"
echo "To push: ./push-images.sh"