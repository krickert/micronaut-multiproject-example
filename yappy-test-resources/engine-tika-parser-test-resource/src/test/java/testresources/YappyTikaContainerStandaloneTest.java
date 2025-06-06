package testresources;

import com.krickert.testcontainers.apicurio.ApicurioContainer;
import com.krickert.yappy.integration.testresources.YappyTikaContainer;
import org.junit.jupiter.api.Test;
import org.testcontainers.consul.ConsulContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers
public class YappyTikaContainerStandaloneTest {

    public static Network network = Network.newNetwork();

    @Container
    public static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
            .withNetwork(network)
            .withNetworkAliases("kafka-for-test");

    @Container
    public static ConsulContainer consul = new ConsulContainer(DockerImageName.parse("hashicorp/consul:1.18"))
            .withNetwork(network)
            .withNetworkAliases("consul-for-test");

    @Container
    public static ApicurioContainer apicurio = new ApicurioContainer()
            .withNetwork(network)
            .withNetworkAliases("apicurio-for-test");

    @Container
    public static YappyTikaContainer yappyContainer = new YappyTikaContainer(
            DockerImageName.parse("engine-tika-parser:latest"),
            "kafka-for-test",
            "consul-for-test",
            "apicurio-for-test",
            "standalone-test-cluster",
            "standalone-test-engine"
    )
            .withNetwork(network)
            // --- THIS IS THE FIX ---
            // Use a lambda for lazy evaluation to match the required Function type
            .withEnv("APICURIO_REGISTRY_URL", ignored -> apicurio.getRegistryApiV3Url())
            .withEnv("KAFKA_PRODUCERS_DEFAULT_VALUE_SERIALIZER", "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer")
            .withEnv("KAFKA_CONSUMERS_DEFAULT_VALUE_DESERIALIZER", "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer")
            .withEnv("KAFKA_PRODUCERS_DEFAULT_KEY_SERIALIZER", "org.apache.kafka.common.serialization.UUIDSerializer")
            .withEnv("KAFKA_CONSUMERS_DEFAULT_KEY_DESERIALIZER", "org.apache.kafka.common.serialization.UUIDDeserializer")
            .withEnv("AWS_REGION", "us-east-1")
            .withEnv("AWS_ACCESS_KEY_ID", "test")
            .withEnv("AWS_SECRET_ACCESS_KEY", "test")
            .dependsOn(kafka, consul, apicurio);

    @Test
    void testContainerIsRunning() {
        assertTrue(yappyContainer.isRunning(), "The container should be running.");
        System.out.println("✅ Yappy Container started successfully in its ecosystem!");
        System.out.println("--> Engine Health Endpoint is accessible from the test host at: http://"
                + yappyContainer.getHost() + ":" + yappyContainer.getMappedPort(8080) + "/health");
    }
}