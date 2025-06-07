# YAPPY Registration CLI

A command-line utility for registering YAPPY modules with the engine.

## Architecture

In the YAPPY architecture:
- Modules are pure gRPC services that implement `PipeStepProcessor`
- Modules expose their capabilities via the `GetServiceRegistration` RPC
- The registration CLI queries modules and registers them with the engine
- The engine handles all Consul registration and service discovery

## Building

```bash
./gradlew :yappy-registration-cli:build
```

This creates an executable JAR at `build/libs/yappy-registration-cli-1.0.0-SNAPSHOT-all.jar`

## Usage

```bash
java -jar yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \
  --module-endpoint <module-host:port> \
  --engine-endpoint <engine-host:port> \
  [options]
```

### Required Arguments

- `--module-endpoint` or `-m`: Module gRPC endpoint (e.g., `localhost:50051`)
- `--engine-endpoint` or `-e`: Engine gRPC endpoint (e.g., `localhost:50050`)

### Optional Arguments

- `--instance-name` or `-n`: Instance service name for Consul registration
  - Default: `<module-name>-instance`
- `--health-type` or `-h`: Health check type: HTTP, GRPC, TCP, TTL
  - Default: `GRPC`
- `--health-path` or `-p`: Health check endpoint path
  - Default: `grpc.health.v1.Health/Check`
- `--version` or `-v`: Module software version

## Examples

### Register tika-parser module

```bash
java -jar yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \
  --module-endpoint localhost:50051 \
  --engine-endpoint localhost:50050 \
  --instance-name tika-parser-prod \
  --health-type GRPC \
  --version 1.0.0
```

### Register with minimal options

```bash
java -jar yappy-registration-cli-1.0.0-SNAPSHOT-all.jar \
  -m localhost:50051 \
  -e localhost:50050
```

## Registration Flow

1. CLI connects to the module at the specified endpoint
2. CLI calls `GetServiceRegistration` to retrieve:
   - Module name
   - JSON configuration schema
3. CLI connects to the engine
4. CLI calls `RegisterModule` with:
   - Module information
   - Instance details
   - Health check configuration
5. Engine registers the module with Consul
6. Engine returns success/failure status

## Testing

A test script is provided for manual testing:

```bash
./test-registration.sh
```

This script builds the CLI and attempts to register a tika-parser module running locally.

## Integration with CI/CD

The registration CLI can be used in deployment pipelines:

```yaml
# Example GitHub Actions workflow
- name: Register Module
  run: |
    java -jar yappy-registration-cli.jar \
      --module-endpoint ${{ env.MODULE_HOST }}:50051 \
      --engine-endpoint ${{ env.ENGINE_HOST }}:50050 \
      --instance-name ${{ env.INSTANCE_NAME }} \
      --version ${{ github.sha }}
```

## Error Handling

The CLI will exit with:
- `0`: Successful registration
- `1`: Registration failed (check logs for details)

Common errors:
- Module unreachable: Check module is running and endpoint is correct
- Engine unreachable: Check engine is running and endpoint is correct
- Registration failed: Check Consul is available and engine has proper permissions