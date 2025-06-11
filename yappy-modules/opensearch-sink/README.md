# OpenSearch Sink Module

This module provides a sink service for indexing documents in OpenSearch. It implements the `SinkService` gRPC interface and converts PipeDoc objects to JSON using Google Protobuf util before indexing them in OpenSearch.

## Features

- Converts PipeDoc objects to JSON using Google Protobuf util
- Indexes documents in OpenSearch
- Supports authentication and SSL
- Configurable index name, bulk size, and retry settings
- Provides a test mode for verifying sink processing without side effects

## Configuration

The OpenSearch sink can be configured using the following properties in `application.yml`:

```yaml
opensearch:
  hosts: localhost                # OpenSearch host(s) to connect to (comma-separated for multiple hosts)
  port: 9200                      # Port number for the OpenSearch cluster
  username:                       # Username for authentication (optional)
  password:                       # Password for authentication (optional)
  use-ssl: false                  # Whether to use SSL for the connection
  index-name: yappy               # Name of the index to write documents to
  index-type: _doc                # Document type for indexing
  id-field: id                    # Field to use as the document ID
  bulk-size: 100                  # Number of documents to include in each bulk request
  bulk-concurrency: 2             # Number of concurrent bulk requests
  max-retries: 3                  # Maximum number of retries for failed requests
  retry-backoff-ms: 1000          # Backoff time in milliseconds between retries
```

## Usage

The OpenSearch sink module provides two gRPC methods:

### processSink

This method processes a PipeStream as a terminal step in the pipeline. It converts the PipeDoc to JSON and indexes it in OpenSearch.

```
rpc processSink(PipeStream) returns (google.protobuf.Empty);
```

### testSink

This method is for testing purposes only. It allows verification of sink processing without side effects.

```
rpc testSink(PipeStream) returns (SinkTestResponse);
```

## Example

Here's an example of how to use the OpenSearch sink in a pipeline:

1. Configure the OpenSearch sink in your pipeline configuration:

```json
{
  "steps": {
    "opensearch-sink": {
      "implementationId": "opensearch-sink",
      "customConfig": {
        "jsonConfig": {
          "hosts": "localhost",
          "port": 9200,
          "indexName": "my-index"
        }
      }
    }
  }
}
```

2. Set the target step name to "opensearch-sink" in your PipeStream:

```java
PipeStream pipeStream = PipeStream.newBuilder()
        .setStreamId(streamId)
        .setDocument(pipeDoc)
        .setCurrentPipelineName("my-pipeline")
        .setTargetStepName("opensearch-sink")
        .build();
```

3. The OpenSearch sink will convert the PipeDoc to JSON and index it in OpenSearch.

## Development

To build the module:

```bash
./gradlew :yappy-modules:opensearch-sink:build
```

To run the tests:

```bash
./gradlew :yappy-modules:opensearch-sink:test
```

To run the integration tests:

```bash
./gradlew :yappy-modules:opensearch-sink:integrationTest
```

## Dependencies

- OpenSearch REST High Level Client
- Google Protobuf Util
- Micronaut gRPC
