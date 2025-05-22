# Semantic Chunker Module

A Python-based YAPPY module that provides semantic chunking functionality for text documents. This module implements the `PipeStepProcessor` gRPC service to process documents in a YAPPY pipeline.

## Features

- Splits text into semantically meaningful chunks
- Creates embeddings for each chunk using sentence transformers
- Groups similar chunks based on semantic similarity
- Preserves sentence boundaries when possible
- Configurable chunk size, overlap, and similarity threshold
- Integrates with the YAPPY platform via gRPC

## Requirements

- Python 3.8+
- Gradle (for building and integration with YAPPY)
- Dependencies listed in `requirements.txt`

## Building and Running

### Generate gRPC Code

Before running the module, you need to generate the Python gRPC code from the protobuf definitions:

```bash
./gradlew generatePythonProto
```

### Install Dependencies

Install the required Python dependencies:

```bash
./gradlew installPythonDependencies
```

### Run Tests

Run the unit tests to ensure everything is working correctly:

```bash
./gradlew pythonTest
```

### Build the Package

Build the Python package:

```bash
./gradlew buildPythonPackage
```

### Run the Service

Start the gRPC service:

```bash
./gradlew runPythonService
```

Or run it directly with Python:

```bash
# Activate the virtual environment
source venv/bin/activate

# Run the service
python src/main/python/semantic_chunker_service.py
```

## Configuration

The semantic chunker can be configured with the following parameters:

- `model_name`: The name of the sentence transformer model to use (default: "all-MiniLM-L6-v2")
- `chunk_size`: Maximum size of each chunk in characters (default: 512)
- `chunk_overlap`: Number of characters to overlap between chunks (default: 50)
- `similarity_threshold`: Threshold for considering chunks semantically similar (default: 0.75)

These parameters can be provided in the custom configuration JSON when setting up the pipeline step in YAPPY.

## Environment Variables

When running the service, the following environment variables can be set:

- `MODULE_HOST`: The host to bind to (default: "0.0.0.0")
- `MODULE_PORT`: The port to bind to (default: "50051")
- `YAPPY_IMPLEMENTATION_ID`: The implementation ID for YAPPY registration (default: "semantic-chunker-v1")
- `YAPPY_REGISTRATION_SERVICE_ADDRESS`: The address of the YAPPY registration service

## Docker

A Dockerfile is provided to containerize the service. Build and run the Docker image with:

```bash
# Build the image
docker build -t semantic-chunker .

# Run the container
docker run -p 50051:50051 \
  -e YAPPY_IMPLEMENTATION_ID=semantic-chunker-v1 \
  -e YAPPY_REGISTRATION_SERVICE_ADDRESS=host.docker.internal:50050 \
  semantic-chunker
```

## Integration with YAPPY

This module implements the `PipeStepProcessor` gRPC service defined in the YAPPY platform. When integrated into a YAPPY pipeline, it will:

1. Receive documents to process
2. Extract the text from the document body
3. Split the text into semantic chunks
4. Create embeddings for each chunk
5. Group similar chunks
6. Add the chunks and embeddings to the document as a `SemanticProcessingResult`
7. Return the processed document

## License

This project is licensed under the same license as the YAPPY platform.