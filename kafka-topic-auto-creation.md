# Kafka Topic Auto-Creation for Pipeline Steps

## Overview

This implementation adds automatic Kafka topic creation when pipeline configurations are added or updated. Previously, Kafka topics had to be manually created before pipeline steps could use them. Now, topics are automatically created when:

1. A new pipeline is added with steps that have `kafkaInputs` defined
2. A new pipeline is added with steps that have `kafkaPublishTopics` defined
3. An existing pipeline is updated to add new Kafka-enabled steps

## Implementation Details

### New Component: PipelineKafkaTopicCreationListener

Located at: `yappy-engine/engine-kafka/src/main/java/com/krickert/search/orchestrator/kafka/listener/PipelineKafkaTopicCreationListener.java`

This component:
- Listens for `PipelineClusterConfigChangeEvent` events
- Identifies pipeline steps that need Kafka topics
- Creates all topic types (input, output, error, dead-letter) for each Kafka-enabled step
- Maintains a cache to avoid duplicate topic creation attempts
- Handles cluster-specific configurations (only processes events for its configured cluster)

### How It Works

1. **Event Reception**: When a pipeline configuration changes (create/update/delete), the `DynamicConfigurationManagerImpl` publishes a `PipelineClusterConfigChangeEvent`

2. **Topic Identification**: The listener examines the pipeline configuration to find steps with:
   - `kafkaInputs` - Steps that consume from Kafka topics
   - `kafkaPublishTopics` - Steps that publish to Kafka topics

3. **Topic Creation**: For each identified step, the listener calls `PipelineKafkaTopicService.createAllTopicsAsync()` which creates:
   - `pipeline.[pipelineName].step.[stepName].input`
   - `pipeline.[pipelineName].step.[stepName].output`
   - `pipeline.[pipelineName].step.[stepName].error`
   - `pipeline.[pipelineName].step.[stepName].dead-letter`

4. **Caching**: Successfully created topics are cached to prevent redundant creation attempts on subsequent configuration updates

### Integration with Existing Components

- **KafkaListenerManager**: Already listens for the same `PipelineClusterConfigChangeEvent` to create Kafka consumers
- **PipelineKafkaTopicService**: Existing service that handles the actual topic creation with proper configurations
- **ConsulBusinessOperationsService**: Triggers events when pipeline configurations are stored

### Testing

Two test suites verify the functionality:

1. **Unit Tests** (`PipelineKafkaTopicCreationListenerTest`):
   - Topic creation for pipelines with Kafka inputs
   - Topic creation for pipelines with Kafka publish topics
   - No duplicate topic creation on repeated events
   - Multiple pipeline steps handling
   - Cluster filtering (ignores events for other clusters)
   - Deletion event handling
   - Error handling for topic creation failures

2. **Integration Tests** (Updated `IncrementalIntegrationTest`):
   - End-to-end test creating a pipeline via API and verifying topic creation
   - Test that pipeline updates without Kafka config changes don't create duplicate topics

### Configuration

The listener is automatically enabled when:
- `kafka.enabled=true` (required by `@Requires` annotation)
- `app.config.cluster-name` is configured (to identify which cluster to manage)

### Benefits

1. **Automatic Setup**: No manual topic creation required before deploying pipelines
2. **Consistency**: All required topics are created with consistent naming and configuration
3. **Error Prevention**: Prevents "topic not found" errors when pipeline steps start
4. **Idempotent**: Safe to process the same configuration multiple times

### Future Enhancements

Potential improvements could include:
- Topic deletion when pipelines are removed
- Topic configuration customization per step
- Monitoring/metrics for topic creation operations
- Batch topic creation for better performance