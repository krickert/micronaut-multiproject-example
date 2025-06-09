# Buffer Enhancement Summary

## Changes Made

### 1. Fixed Buffer Filename Format
- Changed from `engine-requests-{timestamp}{index}.bin` to `engine-requests-{stepName}-{timestamp}-{index}.bin`
- Added dash separator between components for clarity
- Index now uses proper suffix format (-001, -002, etc.)

### 2. Implemented Per-Step Buffers
- Replaced single buffers with per-step buffer maps:
  ```java
  private final Map<String, ProcessingBuffer<ProcessRequest>> requestBuffers = new ConcurrentHashMap<>();
  private final Map<String, ProcessingBuffer<ProcessResponse>> responseBuffers = new ConcurrentHashMap<>();
  private final Map<String, ProcessingBuffer<com.krickert.search.model.PipeDoc>> pipeDocBuffers = new ConcurrentHashMap<>();
  ```
- Buffers are created lazily as steps are executed
- Each step gets its own set of buffers for requests, responses, and documents

### 3. Enhanced Shutdown Logic
- Modified shutdown to save buffers per step:
  ```java
  requestBuffers.forEach((stepName, buffer) -> {
      if (buffer.size() > 0) {
          buffer.saveToDisk("engine-requests-" + stepName + "-" + timestamp, bufferPrecision);
      }
  });
  ```

### 4. Directory Organization
Old format (flat files in working directory):
- `engine-requests-1749440594495000.bin`
- `engine-responses-1749440594495000.bin`
- `engine-pipedocs-1749440594495000.bin`

New format (organized by step with timestamped files):
```
buffer-dumps/
├── tika-parser/
│   ├── requests-1749440594495-001.bin
│   ├── requests-1749440594495-002.bin
│   ├── responses-1749440594495-001.bin
│   └── pipedocs-1749440594495-001.bin
├── chunker-small/
│   ├── requests-1749440775096-001.bin
│   ├── responses-1749440775096-001.bin
│   └── pipedocs-1749440775096-001.bin
└── embedder-step1/
    ├── requests-1749440812043-001.bin
    ├── responses-1749440812043-001.bin
    └── pipedocs-1749440812043-001.bin
```

Each pipeline step gets its own directory, and files are timestamped to keep multiple runs separate.

## Benefits
1. **Better Organization**: Files are organized in directories by timestamp and step
2. **Easier Debugging**: Can quickly identify which step generated which data
3. **Clearer Structure**: Each run gets its own timestamped directory
4. **Per-Step Analysis**: Can analyze data flow through specific steps
5. **Test Data Generation**: Makes it easier to extract test data for specific modules
6. **Clean Working Directory**: Buffer dumps no longer clutter the project root

## Next Steps
1. Run full integration tests to generate new buffer files
2. Create utilities to load these buffer files as test data
3. Add optional connector whitelist filtering
4. Use the captured data to create unit tests for individual modules