# Chunker Pipeline Service

The Chunker Pipeline Service is a microservice that chunks text from a PipeDoc into smaller parts with overlapping sections and stores the chunks in the SemanticDoc structure. This service is designed to be used as part of a pipeline for processing documents.

## Overview

The Chunker service takes a PipeDoc as input, extracts text based on configured mappings (or uses the body field by default), chunks the text into smaller parts with overlapping sections, and stores the chunks in the SemanticDoc structure of the PipeDoc.

The chunking process is performed by the `OverlapChunker` class, which provides methods to chunk text into smaller parts with overlapping sections. The service can be configured to use different chunk sizes and overlap sizes, as well as different fields from the PipeDoc using mappings.

## Configuration

The Chunker service can be configured using the following properties in `application.properties`:

```properties
# Chunker specific configuration
# Default chunk size (characters)
pipeline.configs.chunker-pipeline.service.chunker-service.config-params.chunkSize=500
# Default overlap size (characters)
pipeline.configs.chunker-pipeline.service.chunker-service.config-params.overlapSize=50
# Field mappings (comma-separated list of mapping rules)
# If not specified, the PipeDoc body will be used by default
pipeline.configs.chunker-pipeline.service.chunker-service.config-params.mappings=body = body
```

### Mapping Configuration

The Chunker service uses the ProtoMapper to extract text from the PipeDoc based on configured mappings. If no mapping is defined, it will use the PipeDoc body by default.

Mapping rules are specified as a comma-separated list of strings in the format `target = source`. For example:

```properties
pipeline.configs.chunker-pipeline.service.chunker-service.config-params.mappings=body = title,body = custom_data.field1
```

This would extract the text from the `title` field and the `field1` field in the `custom_data` struct and combine them into the `body` field for chunking.

## Usage Examples

### Basic Usage

To use the Chunker service, send a PipeStream with a PipeDoc to the service. The service will chunk the text from the PipeDoc and store the chunks in the SemanticDoc structure.

```java
// Create a PipeDoc with text to chunk
PipeDoc pipeDoc = PipeDoc.newBuilder()
        .setId("doc-123")
        .setTitle("Example Document")
        .setBody("This is a longer text that will be chunked into multiple parts. " +
                "The chunker service will split this text into smaller chunks with overlapping sections.")
        .build();

// Create a PipeRequest with the PipeDoc
PipeRequest pipeRequest = PipeRequest.newBuilder()
        .setDoc(pipeDoc)
        .build();

// Create a PipeStream with the PipeRequest
PipeStream pipeStream = PipeStream.newBuilder()
        .setRequest(pipeRequest)
        .build();

// Process the PipeStream with the ChunkerPipelineServiceProcessor
PipeServiceDto result = chunkerPipelineServiceProcessor.process(pipeStream);

// Get the processed PipeDoc with chunks
PipeDoc processedDoc = result.getPipeDoc();

// Get the SemanticDoc with chunks
SemanticDoc semanticDoc = processedDoc.getChunkEmbeddings();

// Print the chunks
for (SemanticChunk chunk : semanticDoc.getChunksList()) {
    System.out.println("Chunk " + chunk.getChunkNumber() + ": " + chunk.getEmbedding().getEmbeddingText());
}
```

### Using Mappings

To use mappings to extract text from different fields of the PipeDoc, configure the mappings in `application.properties`:

```properties
pipeline.configs.chunker-pipeline.service.chunker-service.config-params.mappings=body = title,body = custom_data.description
```

This would extract the text from the `title` field and the `description` field in the `custom_data` struct and combine them into the `body` field for chunking.

## Integration with Other Services

The Chunker service can be integrated with other services in the pipeline using Kafka or gRPC. The service listens to the configured Kafka topics for input and publishes the processed PipeStream to the configured output topics.

```properties
# Kafka topics for input and output
pipeline.configs.chunker-pipeline.service.chunker-service.kafka-listen-topics=chunker-input
pipeline.configs.chunker-pipeline.service.chunker-service.kafka-publish-topics=chunker-output
```

## Testing

The Chunker service includes integration tests that verify its functionality with both gRPC and Kafka input methods. The tests use the Apicurio test extension for Kafka testing.

To run the tests:

```bash
./gradlew :pipeline-services:chunker:test
```

## Dependencies

The Chunker service depends on the following components:

- `OverlapChunker`: Provides methods to chunk text into smaller parts with overlapping sections
- `ProtoMapper`: Used for mapping fields between Protocol Buffer messages based on string rules
- `ServiceConfiguration`: Provides configuration for the service
- `PipelineServiceProcessor`: Interface implemented by the service processor