package com.krickert.yappy.integration.testresources;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

public class YappyTikaContainer extends GenericContainer<YappyTikaContainer> {

    // Add a logger for the log consumer
    private static final Logger LOG = LoggerFactory.getLogger(YappyTikaContainer.class);

    public static final String DOCKER_IMAGE_NAME = "engine-tika-parser:latest";

    public YappyTikaContainer(DockerImageName imageName,
                              String kafkaAlias,
                              String consulAlias,
                              String apicurioAlias,
                              String clusterName,
                              String engineName) {
        super(imageName);

        withExposedPorts(8080, 50051, 50053);
        withNetworkAliases("engine-tika-parser");

        // This line streams the container's internal logs to your test output
        withLogConsumer(new Slf4jLogConsumer(LOG));

        waitingFor(
            Wait.forHttp("/health")
                .forPort(8080) // The INTERNAL port inside the container
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofMinutes(3))
        );

        withEnv("KAFKA_BOOTSTRAP_SERVERS", kafkaAlias + ":9092");
        withEnv("CONSUL_HOST", consulAlias);
        withEnv("CONSUL_PORT", "8500");
        withEnv("APICURIO_REGISTRY_URL", "http://" + apicurioAlias + ":8080/apis/registry/v3");
        withEnv("YAPPY_CLUSTER_NAME", clusterName);
        withEnv("YAPPY_ENGINE_NAME", engineName);
        withEnv("CONSUL_ENABLED", "true");
        withEnv("KAFKA_ENABLED", "true");
        withEnv("SCHEMA_REGISTRY_TYPE", "apicurio");
        withEnv("MICRONAUT_SERVER_PORT", "8080");
        withEnv("GRPC_SERVER_PORT", "50051");
    }
}