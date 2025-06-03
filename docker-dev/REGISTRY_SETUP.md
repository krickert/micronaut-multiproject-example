# Docker Registry Setup

## Using External NAS Registry

The development environment is configured to use an external Docker registry running on your NAS at `nas:5000`.

### macOS Docker Desktop Configuration

To use the NAS registry, you need to configure Docker Desktop to trust it as an insecure registry:

1. Open Docker Desktop preferences
2. Go to Docker Engine settings
3. Add the following to the daemon configuration:

```json
{
  "insecure-registries": ["nas:5000"]
}
```

4. Click "Apply & restart"

### Testing the Registry

After configuring Docker Desktop, test the registry:

```bash
# Test pushing to the registry
docker pull hello-world
docker tag hello-world nas:5000/hello-world
docker push nas:5000/hello-world

# If successful, clean up
docker rmi nas:5000/hello-world
```

### Building and Pushing Yappy Containers

Once the registry is configured, you can build and push Yappy containers:

```bash
cd yappy-containers

# Set registry environment variable
export DOCKER_REGISTRY=nas:5000

# Run setup (will skip local registry and test NAS registry)
./setup-local-registry.sh

# Build and push containers
./gradlew dockerBuildImage
./gradlew dockerPushImage
```

### Environment Variables

The following environment variables control registry behavior:

- `NAS_REGISTRY_HOST=nas` - Hostname of the NAS registry
- `DOCKER_REGISTRY_HOST=nas` - Used by setup scripts
- `DOCKER_REGISTRY_PORT=5000` - Registry port
- `START_LOCAL_REGISTRY=false` - Skip starting local registry
- `DOCKER_REGISTRY=nas:5000` - Full registry URL for builds

### Troubleshooting

1. **Registry not accessible**: Ensure your NAS is reachable and the registry is running
2. **Unauthorized error**: Configure Docker to trust the insecure registry
3. **Push fails**: Check that you have write permissions to the NAS registry

### Service URLs

After starting the development environment:

- **NAS Registry**: http://nas:5000
- **Consul UI**: http://localhost:8500
- **Kafka**: localhost:9092 (internal), localhost:9094 (external)
- **Kafka UI**: http://localhost:8081
- **Apicurio**: http://localhost:8080
- **OpenSearch**: http://localhost:9200
- **OpenSearch Dashboards**: http://localhost:5601
- **Moto/Glue**: http://localhost:5001