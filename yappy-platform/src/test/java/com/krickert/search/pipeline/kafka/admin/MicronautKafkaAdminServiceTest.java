package com.krickert.search.pipeline.kafka.admin;

import com.krickert.search.pipeline.kafka.admin.config.KafkaAdminServiceConfig;
import com.krickert.search.pipeline.kafka.admin.exceptions.KafkaAdminServiceException;
import com.krickert.search.pipeline.kafka.admin.exceptions.KafkaOperationTimeoutException;
import com.krickert.search.pipeline.kafka.admin.exceptions.TopicAlreadyExistsException;
import com.krickert.search.pipeline.kafka.admin.exceptions.TopicNotFoundException;
import io.micronaut.scheduling.TaskScheduler;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Uuid;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MicronautKafkaAdminServiceTest {

    @Mock
    private AdminClient mockAdminClient;
    @Mock
    private TaskScheduler mockTaskScheduler;

    private KafkaAdminServiceConfig testConfig;
    private MicronautKafkaAdminService kafkaAdminService;

    @Captor
    private ArgumentCaptor<Collection<NewTopic>> newTopicsCaptor;
    @Captor
    private ArgumentCaptor<Collection<String>> topicNamesCaptor;
    @Captor
    private ArgumentCaptor<Map<ConfigResource, Collection<AlterConfigOp>>> alterConfigsCaptor;
    @Captor
    private ArgumentCaptor<Runnable> runnableCaptor;
    @Captor
    private ArgumentCaptor<Duration> durationCaptor;


    // Helper to create an immediately completed KafkaFuture
    private <T> KafkaFuture<T> immediateFuture(T value) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.complete(value);
        return future;
    }

    // Helper to create an immediately failed KafkaFuture
    private <T> KafkaFuture<T> immediateFailedFuture(Throwable ex) {
        KafkaFutureImpl<T> future = new KafkaFutureImpl<>();
        future.completeExceptionally(ex);
        return future;
    }

    @BeforeEach
    void setUp() {
        testConfig = new KafkaAdminServiceConfig(); // Use default config values
        testConfig.setRequestTimeout(Duration.ofSeconds(5)); // General request timeout for sync calls
        testConfig.setRecreatePollTimeout(Duration.ofSeconds(1)); // Shorter for testing polling timeouts
        testConfig.setRecreatePollInterval(Duration.ofMillis(50)); // Poll frequently

        kafkaAdminService = new MicronautKafkaAdminService(mockAdminClient, testConfig, mockTaskScheduler);
    }

    // --- Test Create Topic ---
    @Test
    void createTopicAsync_success() throws Exception {
        String topicName = "test-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(newTopicsCaptor.capture())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(immediateFuture(null));

        CompletableFuture<Void> resultFuture = kafkaAdminService.createTopicAsync(opts, topicName);
        resultFuture.get(1, TimeUnit.SECONDS); // Wait for completion

        assertTrue(resultFuture.isDone() && !resultFuture.isCompletedExceptionally());
        verify(mockAdminClient).createTopics(any());
        NewTopic capturedTopic = newTopicsCaptor.getValue().iterator().next();
        assertEquals(topicName, capturedTopic.name());
        assertEquals(opts.partitions(), capturedTopic.numPartitions());
        assertEquals(opts.replicationFactor(), capturedTopic.replicationFactor());
        assertTrue(capturedTopic.configs().containsKey("cleanup.policy"));
        assertEquals("delete", capturedTopic.configs().get("cleanup.policy"));
    }

    @Test
    void createTopicAsync_topicExistsException() {
        String topicName = "existing-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(any())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(immediateFailedFuture(new TopicExistsException("Topic exists")));

        CompletableFuture<Void> resultFuture = kafkaAdminService.createTopicAsync(opts, topicName);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> resultFuture.get(1, TimeUnit.SECONDS));
        assertInstanceOf(TopicAlreadyExistsException.class, ex.getCause());
    }

    // --- Test Delete Topic ---
    @Test
    void deleteTopicAsync_success() throws Exception {
        String topicName = "topic-to-delete";
        DeleteTopicsResult mockDeleteTopicsResult = mock(DeleteTopicsResult.class);
        when(mockAdminClient.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockDeleteTopicsResult);
        when(mockDeleteTopicsResult.all()).thenReturn(immediateFuture(null));

        CompletableFuture<Void> resultFuture = kafkaAdminService.deleteTopicAsync(topicName);
        resultFuture.get(1, TimeUnit.SECONDS);

        assertTrue(resultFuture.isDone() && !resultFuture.isCompletedExceptionally());
        verify(mockAdminClient).deleteTopics(topicNamesCaptor.capture());
        assertEquals(topicName, topicNamesCaptor.getValue().iterator().next());
    }

    @Test
    void deleteTopicAsync_topicNotFound() {
        String topicName = "non-existent-topic";
        DeleteTopicsResult mockDeleteTopicsResult = mock(DeleteTopicsResult.class);
        when(mockAdminClient.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockDeleteTopicsResult);
        when(mockDeleteTopicsResult.all()).thenReturn(immediateFailedFuture(new UnknownTopicOrPartitionException("Topic not found")));

        CompletableFuture<Void> resultFuture = kafkaAdminService.deleteTopicAsync(topicName);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> resultFuture.get(1, TimeUnit.SECONDS));
        assertInstanceOf(TopicNotFoundException.class, ex.getCause());
    }


    // --- Test Does Topic Exist ---
    @Test
    void doesTopicExistAsync_exists() throws Exception {
        String topicName = "my-topic";
        ListTopicsResult mockListTopicsResult = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResult);
        when(mockListTopicsResult.names()).thenReturn(immediateFuture(Set.of(topicName, "other-topic")));

        CompletableFuture<Boolean> resultFuture = kafkaAdminService.doesTopicExistAsync(topicName);
        assertTrue(resultFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void doesTopicExistAsync_doesNotExist() throws Exception {
        String topicName = "my-topic";
        ListTopicsResult mockListTopicsResult = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResult);
        when(mockListTopicsResult.names()).thenReturn(immediateFuture(Set.of("other-topic", "another-topic")));

        CompletableFuture<Boolean> resultFuture = kafkaAdminService.doesTopicExistAsync(topicName);
        assertFalse(resultFuture.get(1, TimeUnit.SECONDS));
    }

    @Test
    void doesTopicExistAsync_listTopicsFails() {
        String topicName = "my-topic";
        ListTopicsResult mockListTopicsResult = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResult);
        when(mockListTopicsResult.names()).thenReturn(immediateFailedFuture(new TimeoutException("Kafka timed out")));

        CompletableFuture<Boolean> resultFuture = kafkaAdminService.doesTopicExistAsync(topicName);
        ExecutionException ex = assertThrows(ExecutionException.class, () -> resultFuture.get(1, TimeUnit.SECONDS));
        assertInstanceOf(KafkaAdminServiceException.class, ex.getCause());
        assertTrue(ex.getCause().getMessage().contains("Failed to list topics"));
    }

    // --- Test Recreate Topic ---
    @Test
    void recreateTopicAsync_topicDoesNotExist_createsSuccessfully() {
        String topicName = "new-recreated-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        // Mock for doesTopicExistAsync (used by deleteTopicIfExistsAsync AND pollUntilTopicDeleted)
        // It should consistently report the topic doesn't exist for this test case.
        ListTopicsResult mockListTopicsResult = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResult);
        when(mockListTopicsResult.names()).thenReturn(immediateFuture(Collections.emptySet())); // Topic does not exist

        // Mock for createTopicAsync
        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        lenient().when(mockAdminClient.createTopics(any())).thenReturn(mockCreateTopicsResult);
        lenient().when(mockCreateTopicsResult.all()).thenReturn(immediateFuture(null)); // Create success

        // Capture the first scheduled task (the pollTask) and run it
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1); // get the pollTask
            task.run(); // run it, it should complete pollingFuture quickly
            return null;
        }).when(mockTaskScheduler).schedule(eq(Duration.ZERO), runnableCaptor.capture());


        CompletableFuture<Void> resultFuture = kafkaAdminService.recreateTopicAsync(opts, topicName);
        // Increased timeout slightly to be safe, though it should be fast
        assertDoesNotThrow(() -> resultFuture.get(2, TimeUnit.SECONDS), "Future should complete without timeout");


        assertTrue(resultFuture.isDone() && !resultFuture.isCompletedExceptionally(), "Future should complete successfully");
        // listTopics is called once by deleteTopicIfExistsAsync, then once by the executed pollTask's doesTopicExistAsync
        verify(mockAdminClient, times(2)).listTopics();
        verify(mockAdminClient, times(1)).createTopics(any());
        verify(mockAdminClient, never()).deleteTopics(ArgumentMatchers.<Collection<String>>any());

        // Polling is initiated, so schedule(Duration.ZERO, ...) is called once.
        // The task itself then finds topic doesn't exist and doesn't reschedule.
        verify(mockTaskScheduler, times(1)).schedule(eq(Duration.ZERO), any(Runnable.class));
    }

    @Test
    void recreateTopicAsync_topicExists_deletesPollsAndCreatesSuccessfully() {
        String topicName = "existing-recreated-topic";
        TopicOpts opts = new TopicOpts(2, (short) 1, List.of(CleanupPolicy.COMPACT));

        Queue<KafkaFuture<Set<String>>> listResultsQueue = new LinkedList<>();
        listResultsQueue.add(immediateFuture(Set.of(topicName))); // Initial check: topic exists
        listResultsQueue.add(immediateFuture(Set.of(topicName))); // First poll: topic still exists
        listResultsQueue.add(immediateFuture(Collections.emptySet())); // Second poll: topic deleted

        when(mockAdminClient.listTopics()).thenAnswer(invocation -> {
            ListTopicsResult ltr = mock(ListTopicsResult.class);
            // If queue is empty, means createTopics was called, then subsequent listTopics for verification (not part of recreate)
            // For this specific test, we only care about the sequenced calls during recreate.
            if (listResultsQueue.isEmpty()) {
                // Default for any unexpected listTopics calls after the sequence
                return immediateFuture(Collections.emptySet());
            }
            when(ltr.names()).thenReturn(listResultsQueue.poll());
            return ltr;
        });

        DeleteTopicsResult mockDeleteTopicsResult = mock(DeleteTopicsResult.class);
        when(mockAdminClient.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockDeleteTopicsResult);
        when(mockDeleteTopicsResult.all()).thenReturn(immediateFuture(null));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(newTopicsCaptor.capture())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(immediateFuture(null));

        // Capture and run scheduled tasks
        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run();
            return null;
        }).when(mockTaskScheduler).schedule(durationCaptor.capture(), runnableCaptor.capture());


        CompletableFuture<Void> resultFuture = kafkaAdminService.recreateTopicAsync(opts, topicName);
        assertDoesNotThrow(() -> resultFuture.get(5, TimeUnit.SECONDS)); // Increased timeout for polling simulation

        assertTrue(resultFuture.isDone() && !resultFuture.isCompletedExceptionally());

        verify(mockAdminClient, times(1)).deleteTopics(topicNamesCaptor.capture());
        assertEquals(topicName, topicNamesCaptor.getValue().iterator().next());

        verify(mockAdminClient, times(3)).listTopics(); // 1 initial, 2 polling
        verify(mockTaskScheduler, times(2)).schedule(any(Duration.class), any(Runnable.class));
        List<Duration> capturedDurations = durationCaptor.getAllValues();
        assertEquals(Duration.ZERO, capturedDurations.get(0)); // First schedule call for polling
        assertEquals(testConfig.getRecreatePollInterval(), capturedDurations.get(1)); // Second schedule call

        verify(mockAdminClient, times(1)).createTopics(any());
        NewTopic createdTopic = newTopicsCaptor.getValue().iterator().next();
        assertEquals(topicName, createdTopic.name());
        assertEquals(2, createdTopic.numPartitions());
    }

    @Test
    void recreateTopicAsync_pollingTimesOut() {
        String topicName = "timeout-recreated-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        // Use the recreatePollTimeout set in setUp() (e.g., 1 second)
        // The .get() timeout should be longer to observe the effect of the *internal* logic,
        // but in this specific test setup, we expect the .get() to be the one that fires.
        Duration getOperationTimeout = testConfig.getRecreatePollTimeout().plusSeconds(1); // e.g. 1s + 1s = 2s

        ListTopicsResult mockListTopicsResult = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResult);
        when(mockListTopicsResult.names()).thenReturn(immediateFuture(Set.of(topicName))); // Topic always "exists"

        DeleteTopicsResult mockDeleteTopicsResult = mock(DeleteTopicsResult.class);
        when(mockAdminClient.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockDeleteTopicsResult);
        when(mockDeleteTopicsResult.all()).thenReturn(immediateFuture(null)); // Delete succeeds

        doAnswer(invocation -> {
            Runnable task = invocation.getArgument(1);
            task.run(); // Run the pollTask, which will reschedule itself
            return null;
        }).when(mockTaskScheduler).schedule(any(Duration.class), any(Runnable.class));

        CompletableFuture<Void> resultFuture = kafkaAdminService.recreateTopicAsync(opts, topicName);
        // Corrected Assertion:
        // Expect a direct TimeoutException from CompletableFuture.get() because the mocked
        // scheduler runs polling tasks immediately and synchronously. In this scenario,
        // the System.currentTimeMillis()-based internal timeout in pollUntilTopicDeleted
        // is unlikely to trigger before the .get() itself times out.
        assertThrows(java.util.concurrent.TimeoutException.class,
                () -> resultFuture.get(getOperationTimeout.toMillis(), TimeUnit.MILLISECONDS),
                "Expected a direct TimeoutException from CompletableFuture.get() " +
                        "as the internal polling loop (with immediate task execution) doesn't break " +
                        "and its own System.currentTimeMillis()-based timeout may not fire reliably in this test setup before the .get() times out.");

        verify(mockTaskScheduler, atLeastOnce()).schedule(any(Duration.class), any(Runnable.class));
        verify(mockAdminClient, never()).createTopics(any());
    }


    @Test
    void recreateTopicAsync_deleteFails() {
        String topicName = "delete-fails-recreated-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        ListTopicsResult mockListTopicsResultInitial = mock(ListTopicsResult.class);
        when(mockAdminClient.listTopics()).thenReturn(mockListTopicsResultInitial);
        when(mockListTopicsResultInitial.names()).thenReturn(immediateFuture(Set.of(topicName)));

        DeleteTopicsResult mockDeleteTopicsResult = mock(DeleteTopicsResult.class);
        when(mockAdminClient.deleteTopics(ArgumentMatchers.<Collection<String>>any())).thenReturn(mockDeleteTopicsResult);
        when(mockDeleteTopicsResult.all()).thenReturn(immediateFailedFuture(new TimeoutException("Delete failed"))); // Kafka's Timeout

        CompletableFuture<Void> resultFuture = kafkaAdminService.recreateTopicAsync(opts, topicName);

        ExecutionException ex = assertThrows(ExecutionException.class, () -> resultFuture.get(1, TimeUnit.SECONDS));
        // The service maps Kafka's TimeoutException to our KafkaOperationTimeoutException
        assertInstanceOf(KafkaOperationTimeoutException.class, ex.getCause());

        verify(mockTaskScheduler, never()).schedule(any(Duration.class), any(Runnable.class));
        verify(mockAdminClient, never()).createTopics(any());
    }


    // --- Test Synchronous Wrapper (Example: createTopic) ---
    @Test
    void createTopic_sync_success() {
        String topicName = "sync-test-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(any())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(immediateFuture(null));

        assertDoesNotThrow(() -> kafkaAdminService.createTopic(opts, topicName));
        verify(mockAdminClient).createTopics(newTopicsCaptor.capture());
        assertEquals(topicName, newTopicsCaptor.getValue().iterator().next().name());
    }

    @Test
    void createTopic_sync_timeout() {
        String topicName = "sync-timeout-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(any())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(new KafkaFutureImpl<>()); // Pending future

        KafkaOperationTimeoutException ex = assertThrows(KafkaOperationTimeoutException.class,
                () -> kafkaAdminService.createTopic(opts, topicName)
        );
        assertTrue(ex.getMessage().contains("timed out after " + testConfig.getRequestTimeout().getSeconds() + "s"));
    }

    @Test
    void createTopic_sync_topicExists() {
        String topicName = "sync-existing-topic";
        TopicOpts opts = new TopicOpts(1, (short) 1, List.of(CleanupPolicy.DELETE));

        CreateTopicsResult mockCreateTopicsResult = mock(CreateTopicsResult.class);
        when(mockAdminClient.createTopics(any())).thenReturn(mockCreateTopicsResult);
        when(mockCreateTopicsResult.all()).thenReturn(immediateFailedFuture(new TopicExistsException("Topic exists")));

        assertThrows(TopicAlreadyExistsException.class, () -> kafkaAdminService.createTopic(opts, topicName));
    }

    // --- Other Topic Methods (Brief examples) ---

    @Test
    void listTopicsAsync_success() throws Exception {
        ListTopicsResult mockResult = mock(ListTopicsResult.class);
        Set<String> expectedTopics = Set.of("t1", "t2");
        when(mockAdminClient.listTopics()).thenReturn(mockResult);
        when(mockResult.names()).thenReturn(immediateFuture(expectedTopics));

        CompletableFuture<Set<String>> future = kafkaAdminService.listTopicsAsync();
        assertEquals(expectedTopics, future.get(1, TimeUnit.SECONDS));
    }

    @Test
    void describeTopicAsync_success() throws Exception {
        String topicName = "desc-topic";
        DescribeTopicsResult mockResult = mock(DescribeTopicsResult.class);
        // Corrected constructor call for TopicDescription
        TopicDescription expectedDescription = new TopicDescription(
                topicName,
                false,
                Collections.emptyList(), // partitions
                Collections.emptySet(),  // authorizedOperations
                Uuid.randomUuid()        // topicId
        );
        Map<String, TopicDescription> resultMap = Map.of(topicName, expectedDescription);

        when(mockAdminClient.describeTopics(eq(Collections.singleton(topicName)))).thenReturn(mockResult);
        when(mockResult.allTopicNames()).thenReturn(immediateFuture(resultMap));

        CompletableFuture<TopicDescription> future = kafkaAdminService.describeTopicAsync(topicName);
        assertEquals(expectedDescription, future.get(1, TimeUnit.SECONDS));
    }
    @Test
    void updateTopicConfigurationAsync_success() throws Exception {
        String topicName = "update-config-topic";
        Map<String, String> configsToUpdate = Map.of("retention.ms", "100000");

        AlterConfigsResult mockResult = mock(AlterConfigsResult.class);
        when(mockAdminClient.incrementalAlterConfigs(alterConfigsCaptor.capture())).thenReturn(mockResult);
        when(mockResult.all()).thenReturn(immediateFuture(null));

        CompletableFuture<Void> future = kafkaAdminService.updateTopicConfigurationAsync(topicName, configsToUpdate);
        future.get(1, TimeUnit.SECONDS);

        assertTrue(future.isDone() && !future.isCompletedExceptionally());
        Map<ConfigResource, Collection<AlterConfigOp>> captured = alterConfigsCaptor.getValue();
        ConfigResource capturedResource = captured.keySet().iterator().next();
        assertEquals(topicName, capturedResource.name());
        assertEquals(ConfigResource.Type.TOPIC, capturedResource.type());

        AlterConfigOp capturedOp = captured.values().iterator().next().iterator().next();
        assertEquals("retention.ms", capturedOp.configEntry().name());
        assertEquals("100000", capturedOp.configEntry().value());
        assertEquals(AlterConfigOp.OpType.SET, capturedOp.opType());
    }
}
