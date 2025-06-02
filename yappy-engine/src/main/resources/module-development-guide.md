# Yappy Module Development Guide

## Overview

Yappy modules are language-agnostic gRPC services that implement the `PipeStepProcessor` interface. Modules can be written in any language that supports gRPC (Python, Go, Java, Node.js, Rust, etc.).

## Minimal Module Requirements

Your module only needs to implement two gRPC methods:

### 1. GetServiceRegistration

Returns metadata about your module:

```protobuf
rpc GetServiceRegistration (google.protobuf.Empty) returns (ServiceMetadata);
```

**Required fields:**
- `pipe_step_name`: A unique name for your module (e.g., "text-chunker", "embedder")

**Optional fields:**
- `context_params["json_config_schema"]`: JSON Schema (draft-07) for your configuration
- `context_params["description"]`: Human-readable description of what your module does
- `context_params["version"]`: Module version
- `context_params["author"]`: Module author

### 2. ProcessData

Processes documents in the pipeline:

```protobuf
rpc ProcessData(ProcessRequest) returns (ProcessResponse);
```

**Input:**
- `document`: The PipeDoc to process
- `config`: Configuration for this step (if schema provided)
- `metadata`: Context about the pipeline execution

**Output:**
- `success`: Whether processing succeeded
- `output_doc`: Modified document (optional)
- `processor_logs`: Debug/info messages
- `error_details`: Error information if failed

## Example: Minimal Python Module

```python
import grpc
from concurrent import futures
import yappy_core_types_pb2 as core_types
import pipe_step_processor_service_pb2 as service_pb2
import pipe_step_processor_service_pb2_grpc as service_grpc
from google.protobuf import empty_pb2

class MinimalProcessor(service_grpc.PipeStepProcessorServicer):
    
    def GetServiceRegistration(self, request, context):
        return service_pb2.ServiceMetadata(
            pipe_step_name="example-processor"
        )
    
    def ProcessData(self, request, context):
        # Simply return success
        return service_pb2.ProcessResponse(
            success=True,
            processor_logs=["Processed document: " + request.document.id]
        )

def serve():
    server = grpc.server(futures.ThreadPoolExecutor(max_workers=10))
    service_grpc.add_PipeStepProcessorServicer_to_server(
        MinimalProcessor(), server
    )
    server.add_insecure_port('[::]:50051')
    server.start()
    print("Module listening on port 50051...")
    server.wait_for_termination()

if __name__ == '__main__':
    serve()
```

## Example: Module with Configuration

```python
class ConfigurableProcessor(service_grpc.PipeStepProcessorServicer):
    
    def GetServiceRegistration(self, request, context):
        schema = '''
        {
            "$schema": "http://json-schema.org/draft-07/schema#",
            "type": "object",
            "properties": {
                "mode": {
                    "type": "string",
                    "enum": ["fast", "accurate"],
                    "default": "fast"
                },
                "threshold": {
                    "type": "number",
                    "default": 0.5
                }
            }
        }
        '''
        
        return service_pb2.ServiceMetadata(
            pipe_step_name="configurable-processor",
            context_params={
                "json_config_schema": schema,
                "description": "A processor with configuration options"
            }
        )
    
    def ProcessData(self, request, context):
        # Access configuration
        config = {}
        if request.HasField('config') and request.config.config_data:
            config = MessageToDict(request.config.config_data)
        
        mode = config.get('mode', 'fast')
        threshold = config.get('threshold', 0.5)
        
        # Process based on configuration
        # ... your logic here ...
        
        return service_pb2.ProcessResponse(
            success=True,
            processor_logs=[f"Processed in {mode} mode with threshold {threshold}"]
        )
```

## Module Registration

Once your module is running, it needs to be discoverable:

### Option 1: Consul Tags (Recommended)

Register your service in Consul with the `yappy-module` tag:

```bash
# Using consul CLI
consul services register -name=my-processor -port=50051 -tags=yappy-module,grpc

# Or via HTTP API
curl -X PUT http://consul:8500/v1/agent/service/register -d '{
  "Name": "my-processor",
  "Port": 50051,
  "Tags": ["yappy-module", "grpc"]
}'
```

### Option 2: Naming Convention

Name your service with one of these suffixes:
- `-processor`
- `-module`  
- `-connector`
- `-sink`

The engine will automatically discover services with these patterns.

## Testing Your Module

Use the provided test tool to validate your module:

```bash
# Download the test tool
java -jar yappy-module-tester.jar localhost 50051

# Run specific test
java -jar yappy-module-tester.jar localhost 50051 --test registration
```

The tester validates:
1. ✅ Service registration returns valid metadata
2. ✅ Basic document processing works
3. ✅ Configuration handling (if schema provided)
4. ✅ Error handling with invalid input
5. ✅ Performance with multiple requests
6. ✅ Large document handling
7. ✅ Concurrent request processing

## Best Practices

### 1. Handle Test Mode

Support a test mode for validation:

```python
def ProcessData(self, request, context):
    # Check if in test mode
    if (request.metadata.context_params.get("_test_mode") == "true" or
        request.document.id.startswith("test-doc-")):
        # Minimal processing for testing
        return service_pb2.ProcessResponse(
            success=True,
            processor_logs=["Test mode: validation successful"]
        )
    
    # Regular processing
    # ...
```

### 2. Provide Clear Error Messages

```python
def ProcessData(self, request, context):
    try:
        # Process document
        result = process_document(request.document)
        return service_pb2.ProcessResponse(
            success=True,
            output_doc=result
        )
    except Exception as e:
        return service_pb2.ProcessResponse(
            success=False,
            processor_logs=[f"Error: {str(e)}"],
            error_details={"error_type": type(e).__name__}
        )
```

### 3. Document Your Configuration

Always provide a schema if your module accepts configuration:

```python
def GetServiceRegistration(self, request, context):
    return service_pb2.ServiceMetadata(
        pipe_step_name="my-module",
        context_params={
            "json_config_schema": get_schema(),
            "description": "Processes text using advanced algorithms",
            "version": "1.0.0",
            "min_engine_version": "0.5.0"
        }
    )
```

### 4. Handle Streaming Data

For large documents or streaming operations:

```python
def ProcessData(self, request, context):
    # Check document size
    if len(request.document.body) > 1_000_000:  # 1MB
        # Process in chunks
        return self._process_large_document(request)
    
    # Regular processing
    # ...
```

## Docker Example

```dockerfile
FROM python:3.10-slim

WORKDIR /app

# Copy proto files
COPY protos/ ./protos/

# Install dependencies
RUN pip install grpcio grpcio-tools

# Generate Python code from protos
RUN python -m grpc_tools.protoc \
    -I./protos \
    --python_out=. \
    --grpc_python_out=. \
    ./protos/*.proto

# Copy module code
COPY my_module.py .

# Expose gRPC port
EXPOSE 50051

# Run module
CMD ["python", "my_module.py"]
```

## Module Deployment

```yaml
# docker-compose.yml
version: '3.8'

services:
  my-processor:
    build: .
    ports:
      - "50051:50051"
    environment:
      - CONSUL_HOST=consul
      - CONSUL_PORT=8500
    labels:
      - "yappy.module=true"
      - "yappy.step.name=my-processor"
    
  consul:
    image: consul:latest
    ports:
      - "8500:8500"
```

## Troubleshooting

### Module not discovered

1. Check Consul registration:
   ```bash
   curl http://consul:8500/v1/catalog/service/my-processor
   ```

2. Verify tags or naming convention

3. Check module health in Consul

### Test failures

1. Ensure `pipe_step_name` is provided in registration
2. Return proper success/failure status
3. Handle empty or minimal requests gracefully
4. Check gRPC deadline/timeout settings

### Performance issues

1. Use connection pooling for external services
2. Implement caching where appropriate
3. Process documents in parallel if possible
4. Monitor memory usage with large documents

## Next Steps

1. Review the example modules in `yappy-modules/` directory
2. Use the protobuf definitions in `protobuf-models/`
3. Test your module with the provided test suite
4. Register your module in Consul
5. Configure your module in a pipeline

For questions or issues, please open an issue on the Yappy GitHub repository.