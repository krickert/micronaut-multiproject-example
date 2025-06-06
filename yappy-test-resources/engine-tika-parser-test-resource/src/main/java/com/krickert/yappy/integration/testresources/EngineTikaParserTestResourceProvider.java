package com.krickert.yappy.integration.testresources;

import io.micronaut.testresources.testcontainers.AbstractTestContainersProvider;
import org.testcontainers.utility.DockerImageName;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

// Extending the abstract class simplifies everything.
public class EngineTikaParserTestResourceProvider extends AbstractTestContainersProvider<YappyTikaContainer> {

    public static final String YAPPY_ENGINE_HTTP_URL = "yappy.engine.http.url";
    public static final String YAPPY_ENGINE_GRPC_ENDPOINT = "yappy.engine.grpc.endpoint";
    public static final String YAPPY_TIKA_GRPC_ENDPOINT = "yappy.tika-parser.grpc.endpoint";

    // THIS METHOD IS NOW REQUIRED INSTEAD OF getRequiredProperties
    @Override
    public List<String> getResolvableProperties(Map<String, Collection<String>> propertyEntries, Map<String, Object> testResourcesConfig) {
        return List.of(
            YAPPY_ENGINE_HTTP_URL,
            YAPPY_ENGINE_GRPC_ENDPOINT,
            YAPPY_TIKA_GRPC_ENDPOINT
        );
    }

    @Override
    protected String getSimpleName() {
        // Corresponds to the key in application-test.yml under test-resources.containers
        return "engine-tika-parser";
    }

    @Override
    protected String getDefaultImageName() {
        return YappyTikaContainer.DOCKER_IMAGE_NAME;
    }

    @Override
    protected YappyTikaContainer createContainer(DockerImageName imageName, Map<String, Object> requestedProperties, Map<String, Object> testResourcesConfig) {
        // Get properties from the context to pass to our custom container
        String clusterName = (String) testResourcesConfig.get("yappy.cluster.name");
        String engineName = (String) testResourcesConfig.get("yappy.engine.name");

        // The aliases are defined in your yml file
        return new YappyTikaContainer(imageName, "kafka", "consul", "apicurio", clusterName, engineName);
    }

    @Override
    protected Optional<String> resolveProperty(String propertyName, YappyTikaContainer container) {
        // This method maps the container's dynamic ports to the properties Micronaut needs.
        switch (propertyName) {
            case YAPPY_ENGINE_HTTP_URL:
                return Optional.of("http://" + container.getHost() + ":" + container.getMappedPort(8080));
            case YAPPY_ENGINE_GRPC_ENDPOINT:
                return Optional.of("http://" + container.getHost() + ":" + container.getMappedPort(50051));
            case YAPPY_TIKA_GRPC_ENDPOINT:
                return Optional.of("http://" + container.getHost() + ":" + container.getMappedPort(50053));
        }
        return Optional.empty();
    }
}