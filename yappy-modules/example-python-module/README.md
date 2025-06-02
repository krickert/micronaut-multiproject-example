# Example Python Module: Text Analyzer

This is a complete example of a Yappy pipeline module implemented in Python. It demonstrates all the key concepts needed to build a module in any language.

## What This Module Does

The Text Analyzer module processes documents and adds metadata about the text:
- Word count and character count
- Sentence count and average words per sentence
- Keyword extraction
- Estimated reading time

## Module Structure

```
example-python-module/
├── text_analyzer_module.py   # Main module implementation
├── requirements.txt          # Python dependencies
├── compile_protos.sh        # Script to compile protobuf files
├── test_module.py           # Test client
├── docker-compose.yml       # Run with Consul
└── Dockerfile              # Container image
```

## Key Features Demonstrated

### 1. Service Registration
```python
def GetServiceRegistration(self, request, context):
    return ServiceMetadata(
        pipe_step_name="text-analyzer",
        context_params={
            "json_config_schema": schema,
            "description": "Analyzes text...",
            "module_version": "1.0.0"
        }
    )
```

### 2. Configuration Schema
The module provides a JSON Schema that describes its configuration options:
- `analyze_sentences`: Whether to count sentences
- `analyze_words`: Whether to count words
- `extract_keywords`: Whether to extract keywords
- `add_reading_time`: Whether to calculate reading time
- etc.

### 3. Document Processing
```python
def ProcessData(self, request, context):
    # Extract configuration
    config = self._extract_config(request)
    
    # Process document
    output_doc = self._process_document(request.document, config)
    
    # Return response
    return ProcessResponse(
        success=True,
        output_doc=output_doc,
        processor_logs=["Success"]
    )
```

### 4. Test Mode Support
The module detects test requests and returns minimal responses for validation.

### 5. Error Handling
Proper error handling with structured error details in the response.

### 6. Consul Registration
Optional automatic registration with Consul for discovery.

## Running the Module

### Local Development

1. Install dependencies:
```bash
pip install -r requirements.txt
```

2. Compile proto files:
```bash
./compile_protos.sh
```

3. Run the module:
```bash
python text_analyzer_module.py
```

### With Docker

```bash
docker build -t text-analyzer-module .
docker run -p 50051:50051 text-analyzer-module
```

### With Docker Compose (includes Consul)

```bash
docker-compose up
```

## Testing the Module

### Using the Test Script
```bash
python test_module.py
```

### Using the Yappy Module Tester
```bash
java -jar yappy-module-tester.jar localhost 50051
```

### Manual Testing with grpcurl
```bash
# Get service registration
grpcurl -plaintext localhost:50051 \
  com.krickert.search.sdk.PipeStepProcessor/GetServiceRegistration

# Process a document
grpcurl -plaintext -d '{
  "document": {
    "id": "test-1",
    "title": "Test",
    "body": "This is a test document."
  }
}' localhost:50051 \
  com.krickert.search.sdk.PipeStepProcessor/ProcessData
```

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `analyze_sentences` | boolean | true | Count sentences |
| `analyze_words` | boolean | true | Count words |
| `extract_keywords` | boolean | false | Extract keywords from text |
| `min_keyword_length` | integer | 4 | Minimum keyword length |
| `add_reading_time` | boolean | true | Calculate reading time |
| `words_per_minute` | integer | 200 | Reading speed for time calculation |

## Example Output

When processing a document, the module adds metadata like:

```json
{
  "text_analysis": {
    "word_count": 156,
    "character_count": 892,
    "sentence_count": 8,
    "avg_words_per_sentence": 19.5,
    "estimated_reading_time_minutes": 0.8,
    "reading_time_display": "Less than 1 minute",
    "extracted_keywords": ["document", "text", "analysis", ...]
  }
}
```

## Adapting This Example

To create your own module:

1. **Change the module name** in `GetServiceRegistration`
2. **Define your configuration schema** based on your module's needs
3. **Implement your processing logic** in `_process_document`
4. **Update the Consul tags** if using different categorization
5. **Add your specific dependencies** to requirements.txt

## Best Practices

1. **Always support test mode** - Check for `_test_mode` or `test-doc-` prefix
2. **Provide clear configuration schema** - Use JSON Schema draft-07
3. **Return meaningful error messages** - Help developers debug issues
4. **Log appropriately** - Use proper log levels
5. **Handle missing/invalid input gracefully** - Don't crash on bad data
6. **Keep processing idempotent** - Same input should give same output
7. **Document your module** - Explain what it does and how to use it

## Environment Variables

- `GRPC_PORT`: Port to listen on (default: 50051)
- `CONSUL_ENABLED`: Enable Consul registration (default: false)
- `CONSUL_HOST`: Consul server host (default: localhost)
- `CONSUL_PORT`: Consul server port (default: 8500)

## Troubleshooting

### Module not discovered by engine
1. Check Consul registration succeeded
2. Verify the `yappy-module` tag is present
3. Ensure the module is healthy in Consul

### Test failures
1. Check the module is running on the expected port
2. Verify proto files are compiled correctly
3. Check for Python import errors

### Performance issues
1. Profile your processing logic
2. Consider caching expensive operations
3. Use connection pooling for external services