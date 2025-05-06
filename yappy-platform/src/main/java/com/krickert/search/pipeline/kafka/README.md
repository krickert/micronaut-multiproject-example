Okay, this is a fantastic way to proceed! It's great that the initial draft compiles and that you have a solid testing environment with Testcontainers. Let's break down what we have and chart a path forward with focused, testable steps.

**Analysis of Current `MicronautKafkaAdminService` Implementation:**

First, let's assess what's likely to work "Out Of The Box" (OOTB) versus what needs more attention or is incomplete. This assumes your Micronaut application correctly injects a configured `AdminClient` and Kafka is running.

**Likely to Work OOTB (or with very minor tweaks):**

* **Core Setup:**
    * `AdminClient` injection via constructor.
    * `KafkaAdminServiceConfig` injection and usage for timeouts.
    * The `toCompletableFuture` helper for converting `KafkaFuture`.
    * The basic structure of `mapKafkaException` for common exceptions.
* **Basic Asynchronous Topic Operations:**
    * `createTopicAsync(TopicOpts, String)`: Logic to build `NewTopic` and call `adminClient.createTopics` is sound.
    * `deleteTopicAsync(String)`: Direct call to `adminClient.deleteTopics`.
    * `doesTopicExistAsync(String)`: Using `adminClient.listTopics().names().thenApply(names -> names.contains(topicName))` is reliable.
    * `describeTopicAsync(String)`: Standard `adminClient.describeTopics`.
    * `listTopicsAsync()`: Standard `adminClient.listTopics().names()`.
    * `getTopicConfigurationAsync(String)`: Standard `adminClient.describeConfigs`.
    * `updateTopicConfigurationAsync(String, Map)`: Using `incrementalAlterConfigs` with `OpType.SET` is a good starting point for simple map-based updates.
* **Basic Asynchronous Consumer Group Operations:**
    * `describeConsumerGroupAsync(String)`: Standard `adminClient.describeConsumerGroups`.
    * `listConsumerGroupsAsync()`: Standard `adminClient.listConsumerGroups`.
    * `deleteConsumerGroupAsync(String)`: Standard `adminClient.deleteConsumerGroups`.
* **Asynchronous Lag Monitoring:**
    * `getConsumerLagPerPartitionAsync(String, String)`: The logic of fetching committed offsets and then end offsets to calculate lag is a common and correct pattern.
    * `getTotalConsumerLagAsync(String, String)`: Simple aggregation of per-partition lag.
* **Synchronous Wrappers:** The `waitFor` helper method and the pattern of calling the async method and then `.get(timeout, unit)` with exception handling is a solid approach for providing blocking alternatives.

**Needs More Attention / Might Fail / Incomplete:**

1.  **`recreateTopicAsync` Polling Logic:**
    * **Scheduler Usage:** The manual `Executors.newSingleThreadScheduledExecutor()` should ideally be replaced with Micronaut's `TaskScheduler` for better lifecycle management and testability (e.g., `@Inject TaskScheduler taskScheduler;`). If keeping the manual one, it needs to be properly shut down (e.g., implementing `DisposableBean` in the service).
    * **Polling Error Handling:** The error handling within `pollUntilTopicDeleted` for `doesTopicExistAsync` failures during the poll loop could be more robust. If `doesTopicExistAsync` fails repeatedly for reasons other than the topic still existing (e.g., temporary network issue with Kafka), the polling future might complete with a less specific error or get stuck.
2.  **`resetConsumerGroupOffsetsAsync` (Partially Implemented):**
    * **`TO_TIMESTAMP` Strategy:** The current implementation for `TO_TIMESTAMP` has a note about deciding behavior if no offset is found for a timestamp (e.g., if `offsetsToCommit` remains empty). This needs a defined behavior (e.g., fail, or set to LATEST/EARLIEST for those partitions).
    * **`EARLIEST` / `LATEST` Strategies:** These are explicitly marked as "not fully implemented yet." The logic needs to be filled in:
        1.  Get assigned partitions for the group and topic.
        2.  For each relevant partition, use `adminClient.listOffsets` with `OffsetSpec.earliest()` or `OffsetSpec.latest()`.
        3.  Collect these target offsets and call `adminClient.alterConsumerGroupOffsets()`.
3.  **`mapKafkaException` Nuances:**
    * **`UnknownTopicOrPartitionException` Context:** While generally mapped to `TopicNotFoundException`, in the context of polling *after* a delete operation (like in `recreateTopicAsync`), this exception for the target topic would be the *success* condition for the deletion part. The current `doesTopicExistAsync` handles this correctly by returning `false`. The concern is more if this raw exception ever bypassed `doesTopicExistAsync` during polling.
4.  **Shutdown of `scheduler`:** If not using Micronaut's managed `TaskScheduler`, the manually created `ScheduledExecutorService` in `MicronautKafkaAdminService` needs to be shut down when the application context closes to prevent resource leaks.

---

**Plan: Next Steps**

Let's proceed with a step-by-step plan to implement and test the remaining functionality. Your `KafkaForwarder` will be very useful for testing methods that involve messages and consumer groups (like lag calculation and offset manipulation).

**Step 1: Core Setup, Basic Topic Operations, and `recreateTopic` Refinement**

* **1) Goal:**
    * Ensure the service initializes correctly within the Micronaut framework.
    * Verify the functionality of creating, deleting, describing, listing, and checking the existence of topics.
    * Refine and fully test the `recreateTopicAsync` method, particularly its polling mechanism and scheduler usage.
* **2) Steps to get there:**
    1.  **Refactor Scheduler:** Modify `MicronautKafkaAdminService` to use Micronaut's `TaskScheduler` instead of a manually created `ScheduledExecutorService` for the `recreateTopicAsync` polling logic.
    2.  **Enhance Polling Error Handling (if needed):** Review `pollUntilTopicDeleted` to ensure robust error handling if `doesTopicExistAsync` itself fails during a poll attempt for reasons beyond the topic just existing.
    3.  **Implement Synchronous Wrappers:** Ensure all basic topic operations have their corresponding synchronous wrappers fully implemented and tested using the `waitFor` helper.
* **3) Open Questions:**
    * Are there any specific edge cases for topic configurations (e.g., extremely short retention) that might affect `recreateTopic` behavior or testing? (Probably not critical for initial implementation but good to keep in mind).
* **4) Testing Strategy:**
    * **Unit Tests (Optional for `AdminClient` interaction):** Mock `AdminClient` and `TaskScheduler` to test the internal logic of methods if desired, especially the polling loop logic in isolation.
    * **Integration Tests (Micronaut Test with Testcontainers for Kafka):**
        * Verify `AdminClient` and `KafkaAdminServiceConfig` are injected.
        * `createTopicAsync`/`createTopic`: Create a topic with specific `TopicOpts`, then use `doesTopicExistAsync` and `describeTopicAsync` to verify its creation and configuration.
        * `deleteTopicAsync`/`deleteTopic`: Delete the created topic, verify with `doesTopicExistAsync`.
        * `listTopicsAsync`/`listTopics`: Create a few topics, list them, verify the list.
        * `getTopicConfigurationAsync`/`getTopicConfiguration`: Verify retrieved config matches `TopicOpts`.
        * `updateTopicConfigurationAsync`/`updateTopicConfiguration`: Create a topic, update its config (e.g., retention), describe and verify.
        * `recreateTopicAsync`/`recreateTopic`:
            * Create a topic.
            * Call `recreateTopic` with the same or different `TopicOpts`.
            * Verify the old topic is gone and a new one with the correct properties exists.
            * Test the case where the topic doesn't exist initially; `recreateTopic` should just create it.
            * (Advanced) Potentially test timeout behavior of the polling if possible, though this can be tricky in integration tests.

---

**Step 2: Basic Consumer Group Operations & Lag Monitoring**

* **1) Goal:**
    * Verify the functionality of describing, listing, and deleting consumer groups.
    * Ensure lag calculation methods (`getConsumerLagPerPartitionAsync`, `getTotalConsumerLagAsync`) work correctly.
* **2) Steps to get there:**
    1.  **Implement Synchronous Wrappers:** Ensure all basic consumer group operations and lag monitoring methods have their corresponding synchronous wrappers implemented.
* **3) Open Questions:**
    * None immediately, these are fairly standard `AdminClient` operations.
* **4) Testing Strategy (Integration Tests):**
    * To test these, you'll need a topic with messages and an active consumer group. Your `KafkaForwarder` can produce messages. You'll also need a simple Kafka consumer instance within your test setup.
    * **Setup for Consumer Group Tests:**
        1.  Create a topic (using the admin service from Step 1).
        2.  Use `KafkaForwarder` to send a known number of messages to this topic.
        3.  Start a Kafka consumer (programmatically in your test or as part of your test setup) that subscribes to the topic with a specific `group.id`. Let it consume some, but not all, messages.
    * `listConsumerGroupsAsync`/`listConsumerGroups`: Verify your test consumer group appears.
    * `describeConsumerGroupAsync`/`describeConsumerGroup`: Describe your test group and check its state (e.g., `STABLE`) and assignments.
    * `getConsumerLagPerPartitionAsync`/`getConsumerLag`: Verify the calculated lag matches the expected number of unconsumed messages. Produce more messages and re-check. Let the consumer catch up and verify lag goes to 0.
    * `deleteConsumerGroupAsync`/`deleteConsumerGroup`: Stop your test consumer. Delete its group. Verify it's gone using `listConsumerGroups` and that attempting to describe it fails (throws `ConsumerGroupNotFoundException`).

---

**Step 3: Implement and Test `resetConsumerGroupOffsetsAsync`**

* **1) Goal:**
    * Fully implement and test all strategies for `resetConsumerGroupOffsetsAsync` (`EARLIEST`, `LATEST`, `TO_SPECIFIC_OFFSETS`, `TO_TIMESTAMP`).
* **2) Steps to get there:**
    1.  **Implement `EARLIEST` Strategy:**
        * Get assigned partitions for the group/topic.
        * For each, `adminClient.listOffsets(OffsetSpec.earliest())`.
        * Call `adminClient.alterConsumerGroupOffsets()`.
    2.  **Implement `LATEST` Strategy:**
        * Similar to `EARLIEST`, but use `OffsetSpec.latest()`.
    3.  **Refine `TO_TIMESTAMP` Strategy:**
        * Finalize behavior when no offset is found for a given timestamp for a partition (e.g., default to LATEST for that partition, or fail the reset for that partition, or fail the whole operation if any partition fails). Document the chosen behavior.
    4.  **Implement Synchronous Wrapper:** `resetConsumerGroupOffsets`.
* **3) Open Questions:**
    * For `TO_TIMESTAMP`, what is the desired behavior if a timestamp yields no offset for a partition (e.g., timestamp is out of range of available messages)? Fail that partition's reset, set to LATEST/EARLIEST, or skip? *Initial thought: try to set to LATEST if timestamp is too new, EARLIEST if too old, or make this configurable / throw an error.* For simplicity, failing or skipping the partition if no offset is found might be an initial approach.
* **4) Testing Strategy (Integration Tests):**
    * Requires a topic with messages and an active (or at least recently active) consumer group.
    * **Setup:**
        1.  Create a topic, produce messages (e.g., 100 messages).
        2.  Have a consumer consume some messages (e.g., up to offset 50), then stop it.
    * **`TO_SPECIFIC_OFFSETS`:** Reset offsets to a specific point (e.g., offset 10). Start the consumer again and verify it re-consumes from offset 10.
    * **`EARLIEST`:** Reset offsets to the beginning. Start consumer, verify it consumes all 100 messages.
    * **`LATEST`:** Reset offsets to the end. Start consumer, verify it consumes no old messages (it will wait for new ones).
    * **`TO_TIMESTAMP`:**
        * Produce messages with discernible timestamps (if possible, or use a topic with existing varied timestamps).
        * Choose a timestamp that corresponds to a known offset. Reset to this timestamp.
        * Start consumer, verify it consumes from the expected offset.
        * Test with a timestamp before the earliest message and after the latest message to see how the "no offset found" behavior works.

---

**Step 4: Final Review, Refinements, and Documentation**

* **1) Goal:**
    * Ensure all code paths are robust, exceptions are handled consistently, and the service is well-documented.
* **2) Steps to get there:**
    1.  **Code Review:** Thoroughly review all implemented methods for correctness, efficiency, and error handling.
    2.  **Review Exception Handling:** Ensure `mapKafkaException` is comprehensive for the errors encountered from `AdminClient` calls and that all methods correctly propagate or handle exceptions.
    3.  **Review Timeouts:** Double-check that all synchronous methods use appropriate timeouts from `KafkaAdminServiceConfig`.
    4.  **Add Javadoc:** Ensure all public methods in the interface and implementation, as well as data classes, have clear Javadoc comments explaining their purpose, parameters, return values, and potential exceptions.
    5.  **Consider Edge Cases:** Think about any remaining edge cases (e.g., Kafka cluster unavailable during a call, ACL issues if security is enabled later).
* **3) Open Questions:**
    * Are there any specific logging requirements for this service? (e.g., logging important admin actions).
* **4) Testing Strategy:**
    * Re-run all integration tests.
    * Manual exploratory testing if feasible.
    * Review code coverage reports if available.

---

This phased approach should allow us to build and test the service incrementally. What are your thoughts on this plan? Does Step 1 seem like a good place to start?