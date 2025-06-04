# Container Registry Configuration

## Current Default Configuration
- **Registry**: `nas:5000` (local NAS)
- **Namespace**: `yappy`

## Using Different Registries

### Quick Switch
Use the `registry-config.sh` script to switch between registries:

```bash
# For local NAS (default)
source ./registry-config.sh local

# For GitLab
source ./registry-config.sh gitlab

# For GitHub Container Registry
source ./registry-config.sh github

# For Docker Hub
source ./registry-config.sh dockerhub
```

### Manual Configuration
Set environment variables directly:

```bash
export DOCKER_REGISTRY=<registry-url>
export DOCKER_NAMESPACE=<namespace>
```

## Registry URLs

| Registry | URL | Example Full Path |
|----------|-----|-------------------|
| Local NAS | `nas:5000` | `nas:5000/yappy/tika-parser:latest` |
| GitLab | `registry.gitlab.com` | `registry.gitlab.com/username/yappy/tika-parser:latest` |
| GitHub | `ghcr.io` | `ghcr.io/username/yappy/tika-parser:latest` |
| Docker Hub | `docker.io` | `docker.io/username/tika-parser:latest` |

## Building and Pushing

All build scripts respect the `DOCKER_REGISTRY` and `DOCKER_NAMESPACE` environment variables:

```bash
# Build all containers
./build-all-containers.sh

# Push all containers to configured registry
./push-images.sh

# Build and push specific container
./build-engine-tika.sh
docker push $DOCKER_REGISTRY/$DOCKER_NAMESPACE/engine-tika-parser:latest
```

## Authentication

For remote registries, login first:

```bash
# GitLab
docker login registry.gitlab.com

# GitHub
docker login ghcr.io -u <username>

# Docker Hub
docker login
```

## Current Images

All images follow the pattern: `<registry>/<namespace>/<image-name>:<tag>`

- `engine-tika-parser`
- `engine-chunker`
- `engine-embedder`
- `engine-opensearch-sink`
- `engine-test-connector`