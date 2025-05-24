# S3 Connector Module

This module provides an S3 connector for the YAPPY platform, allowing it to connect to Amazon S3 buckets, list objects, download them, and prepare them for processing by the Tika parser.

## Features

- Connect to Amazon S3 buckets
- List objects with filtering options (prefix, suffix)
- Download objects and extract metadata
- Prepare documents for processing by the Tika parser
- Configurable via JSON configuration

## Configuration Options

The S3 connector supports the following configuration options:

| Option | Description | Default |
|--------|-------------|---------|
| `bucketName` | The name of the S3 bucket to connect to | (required) |
| `region` | The AWS region of the S3 bucket | `us-east-1` |
| `endpoint` | Custom endpoint URL for S3-compatible storage | (AWS S3 endpoint) |
| `accessKey` | AWS access key ID | (from environment) |
| `secretKey` | AWS secret access key | (from environment) |
| `prefix` | Prefix filter for S3 objects | (none) |
| `suffix` | Suffix filter for S3 objects | (none) |
| `recursive` | Whether to recursively list objects | `true` |
| `maxKeys` | Maximum number of objects to list | `100` |
| `kafkaTopic` | Kafka topic to send documents to | (none) |
| `logPrefix` | Prefix for log messages | (none) |

## Example Configuration

```json
{
  "bucketName": "my-documents-bucket",
  "region": "us-west-2",
  "prefix": "documents/",
  "suffix": ".pdf",
  "maxKeys": 50,
  "logPrefix": "[S3Connector] "
}
```

## Usage

The S3 connector is implemented as a gRPC service that implements the `PipeStepProcessor` interface. It can be used as a step in a YAPPY pipeline to connect to an S3 bucket, list objects, download them, and prepare them for processing by the Tika parser.

## Building and Running

To build the module:

```bash
./gradlew :yappy-modules:s3-connector:build
```

To run the module:

```bash
./gradlew :yappy-modules:s3-connector:run
```

## Testing

To run the tests:

```bash
./gradlew :yappy-modules:s3-connector:test
```