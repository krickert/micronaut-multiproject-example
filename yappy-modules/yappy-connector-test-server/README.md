# YAPPY Connector Test Server

A test implementation of the ConnectorEngine gRPC service for testing connectors without requiring a real engine.

## Overview

The YAPPY Connector Test Server is a standalone Micronaut service that implements the ConnectorEngine gRPC service defined in `connector_service.proto`. It is designed to be used for testing connectors without requiring a real engine.

The service provides a simple way to simulate different test scenarios by checking for a `result_response` key in the `initial_context_params` map of the ConnectorRequest. If the value is "success", the request is accepted. If the value is "fail", the request is rejected. If the key is not present, the request is accepted by default.

## Usage

### Starting the Server

To start the server, run the following command:

```bash
./gradlew :yappy-modules:yappy-connector-test-server:run
```

By default, the server will listen on port 50051 for gRPC requests.

### Configuration

The server can be configured using the following properties in `application.yml`:

```yaml
micronaut:
  application:
    name: yappy-connector-test-server
  server:
    port: 8080
  grpc:
    server:
      port: 50051
      keep-alive-time: 3h
      max-inbound-message-size: 10485760  # 10MB
    services:
      connector-test:
        enabled: true
```

### Testing Connectors

To test a connector with this server, configure the connector to send requests to the server's gRPC endpoint. The server will respond based on the `result_response` key in the `initial_context_params` map of the ConnectorRequest:

- If `result_response` is "success", the request will be accepted.
- If `result_response` is "fail", the request will be rejected.
- If `result_response` is not present, the request will be accepted by default.

Example ConnectorRequest:

```java
ConnectorRequest request = ConnectorRequest.newBuilder()
    .setSourceIdentifier("test-source")
    .setDocument(PipeDoc.newBuilder().setId("doc-id").build())
    .putInitialContextParams("result_response", "success")
    .build();
```

## Development

### Adding New Test Scenarios

To add new test scenarios, modify the `ConnectorTestHelper` class to check for additional keys in the `initial_context_params` map and respond accordingly.

### Running Tests

To run the tests, use the following command:

```bash
./gradlew :yappy-modules:yappy-connector-test-server:test
```

## License

This project is licensed under the same license as the YAPPY platform.