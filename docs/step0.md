```markdown
2. **Update Kafka Topic Handling and Validation Strategy:**

    * **Convention:** Input Kafka topics for a consuming step will be named: `yappy.pipeline.<pipelineName>.step.<stepName>.input`
    * **Configuration:**
        * Steps that *publish* to Kafka (e.g., `StepType.KAFKA_PUBLISH` or any step where an
          `OutputTarget.transportType == TransportType.KAFKA`) will continue to have the target topic explicitly defined in
          `OutputTarget.kafkaTransport.topic`.
    * **Validation:**
        * A validator (either enhancing `ReferentialIntegrityValidator` or a new `KafkaConfigurationValidator` in `yappy-consul-config`)
          will:
            * For each `OutputTarget` where `transportType == TransportType.KAFKA`:
                1. Ensure `targetStepName` exists.
                2. Retrieve the `PipelineConfig` and `PipelineStepConfig` for this `targetStepName`. Let's say the target step is
                   `targetStepInstanceConfig` within `targetPipelineConfig`.
                3. Construct the expected conventional input topic for `targetStepName`:
                   `String expectedTopic = "yappy.pipeline." + targetPipelineConfig.getPipelineName() + ".step." + targetStepInstanceConfig.getStepName() + ".input";`
                4. Validate that `outputTarget.getKafkaTransport().getTopic()` equals `expectedTopic`.
```

This sounds great!!!

```markdown
3. **Update Validators (`yappy-consul-config` module):**

    * **`StepTypeValidator.java`:**
        * Modify its `validate` method.
        * If `stepConfig.getStepType() == StepType.GRPC`, then `stepConfig.getProcessorInfo()` must not be null, and
          `stepConfig.getProcessorInfo().getGrpcServiceName()` must be non-blank. Add a validation error if not.
```

Agreed!  Great idea!

```markdown
* If `stepConfig.getStepType() == StepType.INTERNAL`, then `stepConfig.getProcessorInfo()` must not be null, and
  `stepConfig.getProcessorInfo().getInternalProcessorBeanName()` must be non-blank. Add a validation error if not.
```

Great idea!!

```markdown
* (Consider if `KAFKA_PUBLISH` steps *require* `processorInfo` or if their logic is inherently handled by the engine's Kafka forwarder based
  on `OutputTarget`s. For now, let's assume `KAFKA_PUBLISH` doesn't need its own `processorInfo` as it's more of a routing instruction for
  the engine.)
```

It's just a routing instruction. Kafka and grpc forwarding would both work on the same PipeStream and work identically. Kafka and Grpc
and Internal steps ALL work on the same pipesteam and this is all jsut for routing to the right processor implementation.

```markdown
* **`ReferentialIntegrityValidator.java` (or new `KafkaConfigurationValidator.java`):**
    * Implement the Kafka topic validation logic described in point \#2. This will require access to the entire `PipelineClusterConfig` to
      look up target pipelines and steps.
    * The `validate(PipelineClusterConfig clusterConfig)` method in `DefaultConfigurationValidator` iterates through rules, so this
      validator would receive the whole cluster config.
```

Yes.. This is a good step

The DefaultConfigurationValidator needs the full cluster config to ensure "spillage" between pipelines (we will consider how we can do
cross-pipeline processing in a future iteration)

```markdown
* **`CustomConfigSchemaValidator.java`:**
    * This one is a bit trickier as it needs to call out to the `SchemaRegistryService`. For now, we can ensure the field
      `customConfigSchemaId` is present if `customConfig` is also present, but actual schema validation against the registry might be a "
      best effort" at config load time or deferred to runtime just before step execution.
```

So the schema would be the same for that particular step for ALL pipelines and clusters of that particualr implementation. So we would
just do that validation at runtime and then if we get a pipesteam, we have that schema configuration handy and listening, so we just
should need to do a quick jackson validation of the acutal config data.

```markdown
* **Short-term:** Validate that if `customConfig.jsonConfig` is not null/empty, then `customConfigSchemaId` is non-blank.
```

That's great!

```markdown
* **Long-term:** Inject a `SchemaRegistryServiceGrpc.SchemaRegistryServiceBlockingStub` (or an async client) into this validator (or a
  service it uses). When `customConfigSchemaId` is present, call `GetSchema` and then use a JSON schema validation library (like
  `networknt/json-schema-validator` which you seem to have in `yappy-schema-registry`) to validate `customConfig.jsonConfig` against the
  fetched schema content. This makes config loading slower but safer. This might be better suited for the `DynamicConfigurationManagerImpl`
  after loading, rather than a simple validator rule, due to the external call. For now, let's stick to the simple presence check.
```

So getSchema should only need to happen when changes happen in the configuration - right? It wouldn't need to happen for each step.  
The schema for that pipeline isn't going to differ between pipelines or implementations of that particular pipeline, which is why it
"lives" on the application level (even above the cluster level).

4. **Update JSON Test Resources:**

```markdown
* **Location:** `yappy-models/pipeline-config-models/src/test/resources/`
* Modify existing JSON files (e.g., `pipeline-step-config.json`, `grpc-step-example.json`, `internal-step-example.json`, and the overarching
  `pipeline-config-new-model.json`, `pipeline-cluster-config-new-model.json`) to include the new `processorInfo` block with appropriate
  values.
    * For GRPC steps: `"processorInfo": { "grpcServiceName": "yappy-actual-chunker-service" }`
    * For INTERNAL steps: `"processorInfo": { "internalProcessorBeanName": "myInternalTextProcessor" }`
* Adjust Kafka topic names in examples to follow the new convention for *consumer inputs*, and ensure publisher outputs match these.
```

This is great!!! Yes we need to do this...

```markdown
5. **Update and Run Tests:**

    * **POJO Tests:** (`PipelineStepConfigTest.java`, etc. in `yappy-models/pipeline-config-models/src/test/java/...`)
        * Add tests for serialization/deserialization of `PipelineStepConfig` with the new `processorInfo`.
    * **Validator Tests:** (`StepTypeValidatorTest.java`, `ReferentialIntegrityValidatorTest.java`, etc. in
      `yappy-consul-config/src/test/java/...`)
        * Add test cases for the new validation rules related to `processorInfo`.
        * Add test cases for the Kafka topic convention validation. This will likely involve setting up more complex `PipelineClusterConfig`
          instances for testing. You can use the JSON files created in step 4 as a basis for your test data.
        * The `ConsulTestUtil.java` and various test setups will be valuable here.

```

Agreed here too!

IMPLEMENTATION PROVIDED FEEDBACK-

So we just need to delete any micronaut specific things here:

```java
// File: yappy-models/pipeline-config-models/src/main/java/com/krickert/search/config/pipeline/model/PipelineStepConfig.java

package com.krickert.search.config.pipeline.model;

import com.fasterxml.jackson.databind.JsonNode;
import io.micronaut.core.annotation.Introspected;
import jakarta.validation.Valid; // Added
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.jackson.Jacksonized;

import java.util.Map;

@Data
@Builder
@Jacksonized // For Jackson deserialization, includes @JsonDeserialize(builder = PipelineStepConfig.PipelineStepConfigBuilder.class)
@AllArgsConstructor
@NoArgsConstructor
@Introspected // For Micronaut reflection-free bean introspection
public class PipelineStepConfig {

    @NotBlank
    private String stepName;

    @NotNull
    private StepType stepType;

    private String description;

    // Optional: Link to a JSON schema in the Schema Registry for customConfig.jsonConfig
    private String customConfigSchemaId;

    // Configuration specific to this step instance
    // Ensure JsonConfigOptions itself is @Introspected and fields are handled correctly
    @Valid // Added to validate JsonConfigOptions if it has constraints
    private JsonConfigOptions customConfig;

    // Defines how this step connects to subsequent steps
    // The key is a logical output name (e.g., "default", "onSuccess", "onFailure")
    // The value defines the target step and transport mechanism.
    @Valid // Added to validate OutputTarget map entries
    private Map<String, OutputTarget> outputs;

    // Maximum number of retry attempts for this step
    @Builder.Default
    private int maxRetries = 0;

    // Initial backoff period in milliseconds for retries
    @Builder.Default
    private long retryBackoffMs = 1000L;

    // Maximum backoff period in milliseconds for retries
    @Builder.Default
    private long maxRetryBackoffMs = 30000L;

    // Backoff multiplier for retries
    @Builder.Default
    private double retryBackoffMultiplier = 2.0;

    // Timeout for this step's execution in milliseconds
    private Long stepTimeoutMs;

    // NEW: Information about the actual processor implementation for this step
    @NotNull(message = "Processor information (processorInfo) must be provided for each step.")
    @Valid // Enable validation of ProcessorInfo fields if they have constraints
    private ProcessorInfo processorInfo;


    @Data
    @Builder
    @Jacksonized
    @AllArgsConstructor
    @NoArgsConstructor
    @Introspected
    public static class OutputTarget {
        @NotBlank
        private String targetStepName; // The next step this output connects to

        @NotNull
        @Builder.Default
        private TransportType transportType = TransportType.GRPC; // Default or specified

        @Valid // Added
        private GrpcTransportConfig grpcTransport;

        @Valid // Added
        private KafkaTransportConfig kafkaTransport;

        // We could add InternalTransportConfig if needed, though internal calls might not need special transport config.
    }

    @Data
    @Builder
    @Jacksonized
    @AllArgsConstructor
    @NoArgsConstructor
    @Introspected
    public static class JsonConfigOptions {
        // Flexible JSON configuration, structure defined by customConfigSchemaId
        private JsonNode jsonConfig; // Using JsonNode for flexibility

        // Key-value parameters, simpler than full JSON, for basic overrides or settings
        private Map<String, String> configParams;
    }

    // NEW Inner Class for Processor Information
    @Data
    @Builder
    @Jacksonized
    @AllArgsConstructor
    @NoArgsConstructor
    @Introspected
    public static class ProcessorInfo {
        // For StepType.GRPC: Consul service name of the PipeStepProcessor implementing this step
        // e.g., "yappy-chunker-module"
        private String grpcServiceName;

        // For StepType.INTERNAL: Micronaut bean name of the InternalPipeStepProcessor implementation
        // e.g., "myCustomInternalProcessor"
        private String internalProcessorBeanName;

        // Future: Add more fields here if other step types need specific processor identifiers
        // e.g., script path for a SCRIPT type, class name for a reflectively loaded Java type, etc.
    }
}
```
