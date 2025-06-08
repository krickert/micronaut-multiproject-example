# Testing Strategy

## Overview

The YAPPY testing strategy emphasizes real integration tests with actual services, comprehensive coverage, and test-first development. Tests use real infrastructure components via Testcontainers to ensure realistic behavior.

## Testing Principles

### Core Principles

1. **100% Real Services** - No mocks or fake implementations in integration tests
2. **Test-First Development** - Write tests before implementation
3. **Methodical Approach** - Fix one test at a time, verify no regressions
4. **Production-Like Environment** - Tests mirror production setup
5. **Clear Test Boundaries** - Unit tests can use mocks, integration tests cannot

### Test Categories

1. **Unit Tests** - Test individual components in isolation
2. **Integration Tests** - Test component interactions with real services
3. **End-to-End Tests** - Test complete pipeline flows
4. **Performance Tests** - Verify latency and throughput requirements
5. **Contract Tests** - Ensure API compatibility

## Unit Testing

### Component Isolation

Unit tests focus on business logic with mocked dependencies:

```java
@MicronautTest
class PipeStreamStateBuilderTest {
    @MockBean(DynamicConfigurationManager.class)
    DynamicConfigurationManager mockConfigManager() {
        return Mockito.mock(DynamicConfigurationManager.class);
    }
    
    @Test
    void shouldDetermineCorrectRoutes() {
        // Given
        PipelineStepConfig stepConfig = createTestStepConfig();
        when(mockConfigManager.getStepConfig(any(), any()))
            .thenReturn(Optional.of(stepConfig));
        
        // When
        List<RouteData> routes = stateBuilder.determineRoutes(pipeStream);
        
        // Then
        assertThat(routes).hasSize(2);
        assertThat(routes.get(0).transportType()).isEqualTo(TransportType.GRPC);
    }
}
```

### Test Coverage Requirements

- Minimum 80% code coverage
- 100% coverage for critical paths
- All error conditions tested
- Edge cases documented

## Integration Testing

### Self-Contained Engine Testing

Successfully implemented self-contained testing approach:

```java
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EngineWithDummyProcessorIntegrationTest {
    @Inject
    EmbeddedApplication<?> application;
    
    @Inject
    @Client("/")
    HttpClient client;
    
    private Server grpcServer;
    private DummyPipeStepProcessor dummyProcessor;
    
    @BeforeAll
    void startGrpcServer() throws IOException {
        dummyProcessor = new DummyPipeStepProcessor();
        grpcServer = ServerBuilder.forPort(50051)
            .addService(dummyProcessor)
            .build()
            .start();
    }
    
    @Test
    void testFullPipelineProcessing() {
        // Test complete pipeline flow with real services
    }
}
```

### Test Infrastructure

#### Testcontainers Setup

```java
@TestConfiguration
@RequiresTestcontainers
public class TestInfrastructure {
    
    @Bean
    @Primary
    GenericContainer<?> consulContainer() {
        return new GenericContainer<>("consul:1.17")
            .withExposedPorts(8500)
            .withCommand("agent", "-dev", "-client=0.0.0.0");
    }
    
    @Bean
    @Primary
    KafkaContainer kafkaContainer() {
        return new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withKraft();
    }
    
    @Bean
    @Primary
    GenericContainer<?> schemaRegistryContainer() {
        return new GenericContainer<>("apicurio/apicurio-registry-sql:2.5.0")
            .withExposedPorts(8080)
            .withEnv("REGISTRY_DATASOURCE_URL", "jdbc:h2:mem:registry");
    }
}
```

#### Test Resource Management

Custom test resources for automatic setup:

```java
public class ConsulTestResourceProvider implements TestResourcesResolver {
    @Override
    public void resolve(TestResourcesRequest request, 
                       TestResourcesCollector collector) {
        if (request.isPresent("consul.client.host")) {
            GenericContainer<?> consul = new GenericContainer<>("consul:1.17")
                .withExposedPorts(8500);
            consul.start();
            
            collector.addProperty("consul.client.host", consul.getHost());
            collector.addProperty("consul.client.port", 
                consul.getMappedPort(8500).toString());
        }
    }
}
```

### Integration Test Patterns

#### 1. Module Integration Tests

Test actual module implementations:

```java
@MicronautTest
class EchoModuleIntegrationTest {
    @Inject
    EchoService echoService;
    
    @Test
    void testEchoProcessing() {
        // Given
        PipeStream input = createTestPipeStream();
        
        // When
        PipeStream output = echoService.processData(input);
        
        // Then
        assertThat(output.getDocument()).isEqualTo(input.getDocument());
        assertThat(output.getProcessingLogs()).hasSize(1);
    }
}
```

#### 2. Kafka Integration Tests

Test message flow through Kafka:

```java
@MicronautTest
class KafkaPipelineIntegrationTest {
    @Inject
    KafkaProducer<String, PipeStream> producer;
    
    @Inject
    KafkaConsumerFactory consumerFactory;
    
    @Test
    void testKafkaMessageFlow() {
        // Given
        String topic = "test-topic";
        PipeStream message = createTestMessage();
        
        // When
        producer.send(topic, message.getStreamId(), message).blockingGet();
        
        // Then
        try (KafkaConsumer<String, PipeStream> consumer = 
                consumerFactory.createConsumer("test-group")) {
            consumer.subscribe(Collections.singletonList(topic));
            
            ConsumerRecords<String, PipeStream> records = 
                consumer.poll(Duration.ofSeconds(10));
            
            assertThat(records).hasSize(1);
            assertThat(records.iterator().next().value())
                .isEqualTo(message);
        }
    }
}
```

#### 3. Service Discovery Tests

Test Consul-based discovery:

```java
@MicronautTest
class ServiceDiscoveryIntegrationTest {
    @Inject
    DiscoveryClient discoveryClient;
    
    @Inject
    ConsulBusinessOperationsService consulOps;
    
    @Test
    void testServiceRegistrationAndDiscovery() {
        // Given
        Registration registration = createTestRegistration();
        
        // When
        consulOps.registerService(registration).block();
        
        // Then
        Awaitility.await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> {
                List<ServiceInstance> instances = 
                    discoveryClient.getInstances("test-service");
                assertThat(instances).hasSize(1);
            });
    }
}
```

## End-to-End Testing

### Complete Pipeline Tests

Test full pipeline execution:

```java
@MicronautTest
@TestPropertySource(properties = {
    "test.mode=e2e",
    "pipeline.name=test-pipeline"
})
class EndToEndPipelineTest {
    @Inject
    ConnectorEngineGrpc.ConnectorEngineBlockingStub connectorStub;
    
    @Inject
    ConsulBusinessOperationsService consulOps;
    
    @BeforeEach
    void setupPipeline() {
        // Seed pipeline configuration in Consul
        PipelineClusterConfig config = createTestPipelineConfig();
        consulOps.storeClusterConfiguration("test-cluster", config).block();
    }
    
    @Test
    void testCompleteDocumentProcessing() {
        // Given
        ConnectorDocument doc = ConnectorDocument.newBuilder()
            .setDocumentId("test-doc-1")
            .setContent("Test document content")
            .build();
        
        // When
        ConnectorDocumentResult result = 
            connectorStub.processConnectorDoc(doc);
        
        // Then
        assertThat(result.getSuccess()).isTrue();
        
        // Verify document processed through all steps
        verifyDocumentInOpenSearch("test-doc-1");
    }
}
```

### Multi-Module Tests

Test interactions between modules:

```java
@MicronautTest
class MultiModuleIntegrationTest {
    @Test
    void testEchoToChunkerFlow() {
        // Start Echo and Chunker modules
        // Send document through Echo
        // Verify Chunker receives and processes
        // Check final output
    }
}
```

## Performance Testing

### Load Testing

```java
@Test
@EnabledIfSystemProperty(named = "run.performance.tests", matches = "true")
void testHighThroughput() {
    int messageCount = 10000;
    CountDownLatch latch = new CountDownLatch(messageCount);
    
    // Send messages
    Flux.range(0, messageCount)
        .parallel()
        .runOn(Schedulers.parallel())
        .doOnNext(i -> sendTestMessage(i))
        .sequential()
        .blockLast();
    
    // Verify all processed
    boolean completed = latch.await(60, TimeUnit.SECONDS);
    assertThat(completed).isTrue();
    
    // Check metrics
    assertThat(getAverageLatency()).isLessThan(100); // ms
    assertThat(getThroughput()).isGreaterThan(100); // msg/s
}
```

### Latency Testing

```java
@Test
void testProcessingLatency() {
    // Measure end-to-end latency
    long startTime = System.currentTimeMillis();
    
    PipeStream result = engine.processPipe(createTestPipeStream());
    
    long latency = System.currentTimeMillis() - startTime;
    assertThat(latency).isLessThan(500); // 500ms SLA
}
```

## Test Data Management

### Test Data Builders

```java
public class TestDataBuilder {
    public static PipeStream.Builder pipeStreamBuilder() {
        return PipeStream.newBuilder()
            .setStreamId(UUID.randomUUID().toString())
            .setPipelineName("test-pipeline")
            .setTargetStepName("test-step")
            .setDocument(Document.newBuilder()
                .setDocumentId("test-doc")
                .setContent("Test content")
                .build());
    }
    
    public static PipelineClusterConfig clusterConfig() {
        return PipelineClusterConfig.builder()
            .clusterName("test-cluster")
            .pipelineGraphConfig(graphConfig())
            .build();
    }
}
```

### Test Fixtures

Store reusable test data:
```
src/test/resources/
├── fixtures/
│   ├── pipeline-configs/
│   │   ├── simple-pipeline.json
│   │   └── complex-pipeline.json
│   ├── documents/
│   │   ├── sample-doc.json
│   │   └── large-doc.json
│   └── schemas/
│       └── test-schema.json
```

## Testing Best Practices

### 1. Test Organization

- One test class per production class
- Descriptive test method names
- Given-When-Then structure
- Proper cleanup in @AfterEach

### 2. Async Testing

Use Awaitility for async assertions:

```java
Awaitility.await()
    .atMost(Duration.ofSeconds(10))
    .pollInterval(Duration.ofMillis(100))
    .untilAsserted(() -> {
        assertThat(getServiceStatus()).isEqualTo("HEALTHY");
    });
```

### 3. Error Testing

Test all error conditions:

```java
@Test
void shouldHandleInvalidConfiguration() {
    // Given
    PipelineConfig invalidConfig = createInvalidConfig();
    
    // When/Then
    assertThatThrownBy(() -> validator.validate(invalidConfig))
        .isInstanceOf(ValidationException.class)
        .hasMessageContaining("Invalid step reference");
}
```

### 4. Test Isolation

Ensure tests don't affect each other:

```java
@BeforeEach
void cleanupConsul() {
    // Remove all test data from Consul
    consulOps.deleteAllTestData().block();
}

@AfterEach
void cleanupKafka() {
    // Delete test topics
    adminClient.deleteTopics(testTopics).all().get();
}
```

## Continuous Integration

### CI Pipeline

```yaml
name: Test Pipeline

on: [push, pull_request]

jobs:
  unit-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: ./gradlew test

  integration-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
      - run: ./gradlew integrationTest

  e2e-tests:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - run: docker-compose up -d
      - run: ./gradlew e2eTest
      - run: docker-compose down
```

### Test Reports

Generate comprehensive test reports:

```gradle
test {
    reports {
        html.enabled = true
        junitXml.enabled = true
    }
    
    testLogging {
        events "passed", "skipped", "failed"
        exceptionFormat "full"
    }
}
```

## Debugging Failed Tests

### Test Logs

Configure detailed logging for tests:

```xml
<!-- logback-test.xml -->
<configuration>
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.krickert.search" level="DEBUG"/>
    <logger name="io.micronaut.test" level="DEBUG"/>
    
    <root level="INFO">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

### Container Logs

Access test container logs:

```java
@Test
void debugFailingTest() {
    // Print container logs on failure
    try {
        // test code
    } catch (Exception e) {
        System.out.println("Consul logs: " + 
            consulContainer.getLogs());
        System.out.println("Kafka logs: " + 
            kafkaContainer.getLogs());
        throw e;
    }
}
```