package com.krickert.yappy.tests;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import com.ecwid.consul.v1.health.model.HealthService;
import com.ecwid.consul.v1.kv.model.GetValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.krickert.search.config.pipeline.model.*;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Tests engine registration and configuration setup.
 */
public class EngineRegistrationTest extends BaseContainerIntegrationTest {
    
    private static final Logger LOG = LoggerFactory.getLogger(EngineRegistrationTest.class);
    
    private ConsulClient consulClient;
    private ObjectMapper objectMapper;
    private CloseableHttpClient httpClient;
    
    @BeforeEach
    void setup() {
        consulClient = new ConsulClient(getConsulHost(), getConsulPort());
        objectMapper = new ObjectMapper();
        httpClient = HttpClients.createDefault();
    }
    
    @Test
    @DisplayName("Engine should register itself with Consul")
    void testEngineRegistersWithConsul() {
        // Wait for engine to register
        await().atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .untilAsserted(() -> {
                    Response<List<HealthService>> response = consulClient.getHealthServices(
                            "yappy-engine-tika-test", false, QueryParams.DEFAULT);
                    
                    assertThat(response.getValue())
                            .isNotEmpty()
                            .hasSize(1);
                    
                    HealthService service = response.getValue().get(0);
                    assertThat(service.getService().getService()).isEqualTo("yappy-engine-tika-test");
                    assertThat(service.getChecks())
                            .anySatisfy(check -> {
                                assertThat(check.getStatus()).isEqualTo("passing");
                            });
                });
        
        LOG.info("Engine successfully registered with Consul");
    }
    
    @Test
    @DisplayName("Engine should be healthy via HTTP endpoint")
    void testEngineHealthEndpoint() throws Exception {
        String healthUrl = "http://" + getEngineTikaHost() + ":" + getEngineTikaHttpPort() + "/health";
        
        try (CloseableHttpResponse response = httpClient.execute(new HttpGet(healthUrl))) {
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
            
            String body = EntityUtils.toString(response.getEntity());
            assertThat(body).contains("UP");
        }
        
        LOG.info("Engine health endpoint is responding correctly");
    }
    
    @Test
    @DisplayName("Engine should seed default cluster configuration")
    void testDefaultClusterConfiguration() {
        // Check if default cluster configuration is created
        await().atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .untilAsserted(() -> {
                    String key = "pipeline-configs/clusters/test-cluster";
                    Response<GetValue> response = consulClient.getKVValue(key);
                    
                    assertThat(response.getValue()).isNotNull();
                    
                    // Decode and verify the configuration
                    String json = new String(Base64.getDecoder().decode(
                            response.getValue().getValue()), StandardCharsets.UTF_8);
                    
                    PipelineClusterConfig config = objectMapper.readValue(
                            json, PipelineClusterConfig.class);
                    
                    assertThat(config.clusterName()).isEqualTo("test-cluster");
                    assertThat(config.pipelineModuleMap()).isNotNull();
                });
        
        LOG.info("Default cluster configuration successfully created");
    }
    
    @Test
    @DisplayName("Engine should register co-located Tika module in Consul")
    void testTikaModuleRegistration() {
        // The engine should register its co-located Tika module
        await().atMost(30, SECONDS)
                .pollInterval(1, SECONDS)
                .untilAsserted(() -> {
                    // Check if tika-parser service is registered
                    Response<List<HealthService>> response = consulClient.getHealthServices(
                            "tika-parser", false, QueryParams.DEFAULT);
                    
                    assertThat(response.getValue())
                            .isNotEmpty()
                            .anySatisfy(service -> {
                                assertThat(service.getService().getPort()).isEqualTo(50052);
                                assertThat(service.getService().getTags())
                                        .contains("yappy-module");
                            });
                });
        
        LOG.info("Tika module successfully registered in Consul");
    }
    
    @Test
    @DisplayName("Engine should create pipeline configuration with Tika step")
    void testPipelineConfiguration() throws Exception {
        // First, create a pipeline configuration
        PipelineStepConfig tikaStep = PipelineStepConfig.builder()
                .stepName("tika-parser")
                .stepType(StepType.PIPELINE)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("tika-parser")
                        .build())
                .outputs(Map.of("parsed", PipelineStepConfig.OutputTarget.builder()
                        .targetStepName("output")
                        .transportType(TransportType.KAFKA)
                        .kafkaTransport(KafkaTransportConfig.builder()
                                .topic("pipeline.test-pipeline.step.tika-parser.output")
                                .build())
                        .build()))
                .build();
        
        PipelineStepConfig outputStep = PipelineStepConfig.builder()
                .stepName("output")
                .stepType(StepType.SINK)
                .processorInfo(PipelineStepConfig.ProcessorInfo.builder()
                        .grpcServiceName("dummy-sink")
                        .build())
                .outputs(Map.of())
                .build();
        
        PipelineConfig pipelineConfig = new PipelineConfig(
                "test-pipeline",
                Map.of("tika-parser", tikaStep, "output", outputStep)
        );
        
        PipelineGraphConfig graphConfig = PipelineGraphConfig.builder()
                .pipelines(Map.of("test-pipeline", pipelineConfig))
                .build();
        
        PipelineModuleMap moduleMap = new PipelineModuleMap(Map.of(
                "tika-parser", new PipelineModuleConfiguration(
                        "Tika Parser Module",
                        "tika-parser",
                        null,
                        null
                ),
                "dummy-sink", new PipelineModuleConfiguration(
                        "Dummy Sink",
                        "dummy-sink",
                        null,
                        null
                )
        ));
        
        PipelineClusterConfig clusterConfig = PipelineClusterConfig.builder()
                .clusterName("test-cluster")
                .pipelineGraphConfig(graphConfig)
                .pipelineModuleMap(moduleMap)
                .allowedKafkaTopics(Set.of(
                        "pipeline.test-pipeline.step.tika-parser.output"
                ))
                .allowedGrpcServices(Set.of("tika-parser", "dummy-sink"))
                .build();
        
        // Store configuration in Consul
        String key = "pipeline-configs/clusters/test-cluster";
        String json = objectMapper.writeValueAsString(clusterConfig);
        consulClient.setKVValue(key, json);
        
        // Wait for engine to load the configuration
        Thread.sleep(2000);
        
        // Verify configuration was loaded (we could check logs or add an admin endpoint)
        LOG.info("Pipeline configuration stored successfully");
    }
}