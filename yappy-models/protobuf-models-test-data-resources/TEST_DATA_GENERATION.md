# Test Data Generation Guide

This module contains test data resources for protobuf models. The test data files can be regenerated with deterministic IDs to avoid constant changes in version control.

## Problem Solved

Previously, test data files were being regenerated with random UUIDs every time tests were run, causing:
- Unnecessary git changes
- Large diffs in pull requests
- Confusion about which changes were intentional

## Solution

The test data generation system now provides:

1. **Optional Generation**: Test data is only regenerated when explicitly requested
2. **Deterministic IDs**: IDs are generated based on content hash or index, ensuring consistency
3. **Flexible Output**: Data can be written to temp directories or directly to resources

## Configuration

### System Properties

- `yappy.test.data.regenerate`: Enable test data regeneration (default: `false`)
- `yappy.test.data.deterministic`: Use deterministic IDs (default: `true`)
- `yappy.test.data.output.dir`: Output directory (default: system temp dir)

### Usage

#### View Configuration Options
```bash
./gradlew :yappy-models:protobuf-models-test-data-resources:showTestDataConfig
```

#### Regenerate Test Data (to temp directory)
```bash
./gradlew :yappy-models:protobuf-models-test-data-resources:regenerateTestData
```

This will:
- Generate test data with deterministic IDs
- Write to a temporary directory
- Display instructions for copying to resources

#### Regenerate Test Data (directly to resources)
```bash
./gradlew :yappy-models:protobuf-models-test-data-resources:regenerateTestData -PwriteToResources=true
```

This will:
- Generate test data with deterministic IDs
- Write directly to `src/main/resources/test-data/sample-documents`
- Overwrite existing files

#### Generate with Random IDs (not recommended)
```bash
./gradlew :yappy-models:protobuf-models-test-data-resources:regenerateTestData -Prandom=true
```

### Running Tests Without Regeneration

By default, tests that generate data will skip generation:
```bash
./gradlew test
```

### Manual Test Execution with Regeneration

To run specific tests with regeneration enabled:
```bash
./gradlew test -Dyappy.test.data.regenerate=true -Dyappy.test.data.deterministic=true
```

## Implementation Details

### Deterministic ID Generation

IDs are generated using one of these methods:
1. **Content-based**: SHA-256 hash of the content (first 8 characters)
2. **Index-based**: Hexadecimal representation of the index
3. **Composite**: Combination of prefix, index, and content hash

### Affected Test Classes

The following test classes generate test data:
- `TikaDocumentProcessorTest`: Generates sample PipeDoc and PipeStream files
- `ChunkerDataSimulatorTest`: Generates chunked document test data

## Best Practices

1. **Regular Development**: Don't regenerate test data unless necessary
2. **Adding New Test Data**: Use deterministic mode to ensure consistency
3. **Updating Test Data**: Regenerate to temp first, review changes, then update resources
4. **CI/CD**: Never regenerate test data in CI pipelines

## Troubleshooting

### Test Data Not Being Generated
- Check that regeneration is enabled: `-Dyappy.test.data.regenerate=true`
- Verify the output directory has write permissions

### IDs Keep Changing
- Ensure deterministic mode is enabled: `-Dyappy.test.data.deterministic=true`
- Check that the content being hashed is consistent

### Can't Find Generated Files
- Check the console output for the actual output directory
- Look for files in the system temp directory under `/yappy-test-data`