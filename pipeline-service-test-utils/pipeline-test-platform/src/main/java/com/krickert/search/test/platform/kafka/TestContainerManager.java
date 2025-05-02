// <llm-snippet-file>pipeline-service-test-utils/pipeline-test-platform/src/main/java/com/krickert/search/test/platform/kafka/TestContainerManager.java</llm-snippet-file>
package com.krickert.search.test.platform.kafka;

import com.krickert.search.test.platform.consul.ConsulContainer;
import com.krickert.search.test.platform.kafka.registry.MotoSchemaRegistry;
import io.apicurio.registry.serde.config.SerdeConfig;
import lombok.Getter;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TestContainerManager {
    private static final Logger log = LoggerFactory.getLogger(TestContainerManager.class);
    private static volatile TestContainerManager instance;
    private final Map<String, String> properties = new ConcurrentHashMap<>();

    // Property Keys
    public static final String KAFKA_BOOTSTRAP_SERVERS_PROP = "kafka.bootstrap.servers";
    public static final String KAFKA_REGISTRY_TYPE_PROP = "kafka.schema.registry.type";
    public static final String APICURIO_REGISTRY_URL_PROP = "apicurio.registry.url";
    // --- Glue Properties (Expected to be configured externally) ---
    public static final String GLUE_REGISTRY_URL_PROP = "glue.registry.url"; // Key for the externally provided URL
    public static final String GLUE_REGISTRY_NAME_PROP = "glue.registry.name";
    public static final String AWS_ACCESS_KEY_PROP = "aws.accessKeyId";
    public static final String AWS_SECRET_KEY_PROP = "aws.secretAccessKey";
    public static final String AWS_REGION_PROP = "aws.region";
    // --- Moto Properties ---
    public static final String MOTO_REGISTRY_URL_PROP = "moto.registry.url";
    public static final String MOTO_REGISTRY_NAME_PROP = "moto.registry.name";

    @Getter
    private String registryType = "none";

    @Getter
    private ConsulContainer consulContainer;
    @Getter
    private GenericContainer<?> schemaRegistryContainer; // Only for Apicurio
    // --- Removed LocalStackContainer ---
    @Getter
    private KafkaContainer kafkaContainer;
    @Getter
    private MotoSchemaRegistry motoSchemaRegistry; // For Moto
    private final Network network;
    private volatile boolean initialized = false;

    // Container Images
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:latest");
    private static final DockerImageName APICURIO_IMAGE = DockerImageName.parse("apicurio/apicurio-registry:latest");

    // Ser/De classes
    private static final String APICURIO_PROTOBUF_SERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaSerializer";
    private static final String APICURIO_PROTOBUF_DESERIALIZER_CLASS = "io.apicurio.registry.serde.protobuf.ProtobufKafkaDeserializer";
    private static final String GLUE_SERIALIZER_CLASS = "com.amazonaws.services.schemaregistry.serializers.GlueSchemaRegistryKafkaSerializer";
    private static final String GLUE_DESERIALIZER_CLASS = "com.amazonaws.services.schemaregistry.deserializers.GlueSchemaRegistryKafkaDeserializer";
    private static final String UUID_SERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDSerializer";
    private static final String UUID_DESERIALIZER_CLASS = "org.apache.kafka.common.serialization.UUIDDeserializer";

    // Default/Dummy AWS Config (still useful for Glue client library defaults)
    private static final String DEFAULT_AWS_REGION = System.getProperty("aws.region", "us-east-1");
    private static final String DUMMY_ACCESS_KEY = "test-access-key"; // Needed by Glue SerDe lib
    private static final String DUMMY_SECRET_KEY = "test-secret-key"; // Needed by Glue SerDe lib
    private static final String DEFAULT_GLUE_REGISTRY_NAME = "default-registry";

    // Static configuration map (used before first getInstance call)
    private static final Map<String, String> preInitProperties = new ConcurrentHashMap<>();

    private TestContainerManager() {
        // Determine registry type from pre-configured properties first, then default to apicurio
        this.registryType = preInitProperties.getOrDefault(KAFKA_REGISTRY_TYPE_PROP, "apicurio");
        log.info("Initializing TestContainerManager with registry type: {}", this.registryType);

        // Use common network for all containers
        this.network = Network.newNetwork();
        log.debug("Created Docker network with ID: {}", network.getId());

        // Initialize Consul
        this.consulContainer = new ConsulContainer(network)
                .withNetwork(network)
                .withNetworkAliases("consul");
        log.info("Consul container configured.");

        // Initialize Kafka
        this.kafkaContainer = new KafkaContainer(KAFKA_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("kafka");
        log.info("Kafka container configured.");

        // Initialize Schema Registry based on type
        if ("apicurio".equalsIgnoreCase(this.registryType)) {
            this.schemaRegistryContainer = new GenericContainer<>(APICURIO_IMAGE)
                    .withExposedPorts(8080)
                    .withEnv("QUARKUS_PROFILE", "prod")
                    .withNetwork(network)
                    .withNetworkAliases("apicurio-registry")
                    .withStartupTimeout(Duration.ofSeconds(120));
            log.info("Apicurio Registry container configured.");
            this.motoSchemaRegistry = null;
        } else if ("moto".equalsIgnoreCase(this.registryType)) {
            this.schemaRegistryContainer = null;
            log.info("Moto registry type configured. TestContainerManager will initialize MotoSchemaRegistry.");
            // MotoSchemaRegistry will be initialized in startManagedContainers
            this.motoSchemaRegistry = null; // Will be initialized later
        } else {
            // For Glue or none, we don't start a registry container here
            this.schemaRegistryContainer = null;
            this.motoSchemaRegistry = null;
            if ("glue".equalsIgnoreCase(this.registryType)) {
                log.info("Glue registry type configured. TestContainerManager will NOT start a Glue container. Ensure '{}' property is set externally.", GLUE_REGISTRY_URL_PROP);
            } else {
                log.info("No schema registry container configured (type: {}).", this.registryType);
            }
        }

        // Start ONLY the containers managed by this class
        startManagedContainers();

        // Populate properties map AFTER containers are started
        // Crucially, this will now read external props for Glue
        populateProperties();

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::stopContainers));
        this.initialized = true;
        log.info("TestContainerManager initialized successfully.");
    }


    public static synchronized void configure(Map<String, String> initialProperties) {
        if (instance != null) {
            log.warn("TestContainerManager already initialized. Configuration attempt ignored.");
            return;
        }
        // Store all provided properties, including potential glue.registry.url etc.
        preInitProperties.putAll(initialProperties);
        log.info("Pre-configured TestContainerManager properties: {}", initialProperties.keySet());
    }

    public static synchronized TestContainerManager getInstance() {
        if (instance == null) {
            log.info("Creating TestContainerManager instance...");
            instance = new TestContainerManager();
            // Apply any remaining pre-init properties (might overwrite defaults if set late)
            // But ideally, configuration happens *before* getInstance() via configure()
            instance.properties.putAll(preInitProperties);
            preInitProperties.clear(); // Clear after use
        }
        return instance;
    }

    public synchronized void setProperty(String key, String value) {
        log.debug("Setting property: {} = {}", key, value);
        properties.put(key, value);
        // Re-populate might be needed if crucial props like registry URL change AFTER init
        // This is complex, better to configure upfront. For now, just store.
        if (initialized) {
            log.warn("Property '{}' set after initialization. Re-running populateProperties() might be needed depending on the property.", key);
            // Consider re-running populateProperties() or parts of it if needed
        }
    }

    private synchronized void startManagedContainers() {
        log.info("Starting managed containers...");
        try {
            // Start common containers
            consulContainer.start();
            log.info("Consul container started.");
            kafkaContainer.start();
            log.info("Kafka container started.");

            // Start Apicurio if configured
            if (schemaRegistryContainer != null) {
                schemaRegistryContainer.start();
                log.info("Apicurio schema registry container started.");
            }

            // Initialize MotoSchemaRegistry if configured
            if ("moto".equalsIgnoreCase(this.registryType)) {
                this.motoSchemaRegistry = new MotoSchemaRegistry();
                log.info("MotoSchemaRegistry initialized.");
            }

            log.info("All managed containers started.");
        } catch (Exception e) {
            log.error("Failed to start one or more managed containers", e);
            stopContainers(); // Attempt cleanup
            throw new RuntimeException("Managed container startup failed", e);
        }
    }


    private void populateProperties() {
        log.debug("Populating properties map...");
        if (!isKafkaRunning() || !isConsulRunning()) {
            log.warn("Attempted to populate properties before Kafka/Consul were running.");
        }
        // Always add Kafka and Consul properties from the managed containers
        properties.put(KAFKA_BOOTSTRAP_SERVERS_PROP, kafkaContainer.getBootstrapServers());
        properties.put("kafka.brokers", kafkaContainer.getBootstrapServers());
        properties.put("consul.host", consulContainer.getHost());
        properties.put("consul.port", String.valueOf(consulContainer.getMappedPort(8500)));
        properties.put("consul.client.defaultZone", "http://" + consulContainer.getHost() + ":" + consulContainer.getMappedPort(8500));
        properties.put("consul.client.registration.enabled", "true");

        // Add registry-specific properties
        String serializerClass = UUID_SERIALIZER_CLASS; // Default
        String deserializerClass = UUID_DESERIALIZER_CLASS; // Default

        if ("apicurio".equalsIgnoreCase(registryType)) {
            String registryUrl = getSchemaRegistryEndpointInternal(); // Gets from Apicurio container
            if (registryUrl != null && !registryUrl.isBlank()) {
                serializerClass = APICURIO_PROTOBUF_SERIALIZER_CLASS;
                deserializerClass = APICURIO_PROTOBUF_DESERIALIZER_CLASS;
                properties.put(APICURIO_REGISTRY_URL_PROP, registryUrl);
                properties.put("kafka.producers.default." + SerdeConfig.REGISTRY_URL, registryUrl);
                properties.put("kafka.producers.default." + SerdeConfig.AUTO_REGISTER_ARTIFACT, "true");
                properties.put("kafka.consumers.default." + SerdeConfig.REGISTRY_URL, registryUrl);
            } else {
                log.error("Apicurio registry configured but endpoint is not available!");
            }

        } else if ("moto".equalsIgnoreCase(registryType)) {
            if (motoSchemaRegistry == null) {
                log.error("Moto registry type is configured, but MotoSchemaRegistry is not initialized!");
            } else {
                log.info("Configuring Kafka clients for Moto registry at endpoint: {}", motoSchemaRegistry.getEndpoint());
                serializerClass = motoSchemaRegistry.getSerializerClass();
                deserializerClass = motoSchemaRegistry.getDeserializerClass();

                // Add Moto properties to the properties map
                properties.put(MOTO_REGISTRY_URL_PROP, motoSchemaRegistry.getEndpoint());
                properties.put(MOTO_REGISTRY_NAME_PROP, motoSchemaRegistry.getRegistryName());

                // Add all properties from MotoSchemaRegistry
                properties.putAll(motoSchemaRegistry.getProperties());
            }

        } else if ("glue".equalsIgnoreCase(registryType)) {
            // --- Read Glue properties from the map (must be pre-configured) ---
            String registryUrl = properties.get(GLUE_REGISTRY_URL_PROP); // Get pre-configured URL
            String glueRegistryName = properties.getOrDefault(GLUE_REGISTRY_NAME_PROP, DEFAULT_GLUE_REGISTRY_NAME);
            String awsRegion = properties.getOrDefault(AWS_REGION_PROP, DEFAULT_AWS_REGION);
            String awsAccessKey = properties.getOrDefault(AWS_ACCESS_KEY_PROP, DUMMY_ACCESS_KEY);
            String awsSecretKey = properties.getOrDefault(AWS_SECRET_KEY_PROP, DUMMY_SECRET_KEY);

            if (registryUrl == null || registryUrl.isBlank()) {
                log.error("Glue registry type is configured, but the '{}' property is missing or empty! Kafka clients will likely fail.", GLUE_REGISTRY_URL_PROP);
                // Still set SerDe classes, but they won't work without the URL
                serializerClass = GLUE_SERIALIZER_CLASS;
                deserializerClass = GLUE_DESERIALIZER_CLASS;
            } else {
                log.info("Configuring Kafka clients for Glue registry at endpoint: {}", registryUrl);
                serializerClass = GLUE_SERIALIZER_CLASS;
                deserializerClass = GLUE_DESERIALIZER_CLASS;

                // Add the EXTERNALLY provided URL to the properties if it wasn't already there via setProperty/configure
                properties.putIfAbsent(GLUE_REGISTRY_URL_PROP, registryUrl);
                properties.putIfAbsent(GLUE_REGISTRY_NAME_PROP, glueRegistryName);
                properties.putIfAbsent(AWS_REGION_PROP, awsRegion);
                properties.putIfAbsent(AWS_ACCESS_KEY_PROP, awsAccessKey);
                properties.putIfAbsent(AWS_SECRET_KEY_PROP, awsSecretKey);

                // Configure properties for the Glue SerDe library
                String producerPrefix = "kafka.producers.default.value.serializer.";
                String consumerPrefix = "kafka.consumers.default.value.deserializer.";

                properties.put(producerPrefix + "glue.endpoint.url", registryUrl); // Use the configured endpoint
                properties.put(producerPrefix + "glue.region", awsRegion);
                properties.put(producerPrefix + "glue.registry.name", glueRegistryName);
                properties.put(producerPrefix + "glue.credentials.accessKey", awsAccessKey); // Pass configured credentials
                properties.put(producerPrefix + "glue.credentials.secretKey", awsSecretKey);
                properties.put("kafka.producers.default." + "value.serializer.glue.schema.auto.registration.enabled", "true");


                properties.put(consumerPrefix + "glue.endpoint.url", registryUrl); // Use the configured endpoint
                properties.put(consumerPrefix + "glue.region", awsRegion);
                properties.put(consumerPrefix + "glue.registry.name", glueRegistryName);
                properties.put(consumerPrefix + "glue.credentials.accessKey", awsAccessKey); // Pass configured credentials
                properties.put(consumerPrefix + "glue.credentials.secretKey", awsSecretKey);
            }
        } else {
            log.info("No schema registry endpoint configured ({})", registryType);
        }

        // Set the final determined Ser/De classes
        properties.put("kafka.producers.default." + ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, UUID_SERIALIZER_CLASS);
        properties.put("kafka.producers.default." + ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, serializerClass);
        properties.put("kafka.producers.default." + ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        properties.put("kafka.consumers.default." + ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, UUID_DESERIALIZER_CLASS);
        properties.put("kafka.consumers.default." + ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, deserializerClass);
        properties.put("kafka.consumers.default." + ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers());
        String KAFKA = "kafka.";
        // Add the registry type itself
        properties.put(KAFKA + KAFKA_REGISTRY_TYPE_PROP, this.registryType);
        properties.put(KAFKA + AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers()); // Uses updated method
        properties.put(KAFKA + AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "30000"); // Consider making timeouts configurable
        properties.put(KAFKA + AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "30000");

        log.info("Properties populated: {}", properties.keySet());
    }

    // Return the endpoint based on type
    private String getSchemaRegistryEndpointInternal() {
        if ("apicurio".equalsIgnoreCase(registryType)) {
            if (schemaRegistryContainer != null && schemaRegistryContainer.isRunning()) {
                return String.format("http://%s:%d/apis/registry/v3",
                        schemaRegistryContainer.getHost(),
                        schemaRegistryContainer.getMappedPort(8080));
            } else {
                log.error("Apicurio container is not running or null when getting endpoint.");
                return null;
            }
        } else if ("moto".equalsIgnoreCase(registryType)) {
            if (motoSchemaRegistry != null) {
                return motoSchemaRegistry.getEndpoint();
            } else {
                log.error("MotoSchemaRegistry is not initialized when getting endpoint.");
                return null;
            }
        } else if ("glue".equalsIgnoreCase(registryType)) {
            // For Glue, return the configured property value
            String glueUrl = properties.get(GLUE_REGISTRY_URL_PROP);
            if (glueUrl == null || glueUrl.isBlank()){
                log.warn("Requesting Glue endpoint, but '{}' property is not set.", GLUE_REGISTRY_URL_PROP);
            }
            return glueUrl;
        }
        // No registry configured or unknown type
        return null;
    }

    public Map<String, String> getProperties() {
        if (!initialized) {
            log.warn("Accessing properties before TestContainerManager is fully initialized.");
            // Populate if not already? Could be risky. Better ensure getInstance() is called first.
            // populateProperties(); // Maybe?
        }
        return Collections.unmodifiableMap(properties);
    }

    // --- Container Status Checks ---

    public boolean isKafkaRunning() {
        return kafkaContainer != null && kafkaContainer.isRunning();
    }

    public boolean isSchemaRegistryRunning() {
        // Check registry status based on type
        if ("glue".equalsIgnoreCase(registryType)) {
            // For Glue, we assume the external service is "running" if configured
            String glueUrl = properties.get(GLUE_REGISTRY_URL_PROP);
            boolean configured = glueUrl != null && !glueUrl.isBlank();
            if (!configured) log.warn("Glue registry check: URL property '{}' is not set.", GLUE_REGISTRY_URL_PROP);
            return configured; // Return true if the URL is configured, false otherwise
        } else if ("moto".equalsIgnoreCase(registryType)) {
            // For Moto, check if the MotoSchemaRegistry is initialized and running
            boolean running = motoSchemaRegistry != null && motoSchemaRegistry.isRunning();
            if (!running) log.warn("Moto registry check: MotoSchemaRegistry is not initialized or not running.");
            return running;
        } else if ("apicurio".equalsIgnoreCase(registryType)) {
            return schemaRegistryContainer != null && schemaRegistryContainer.isRunning();
        } else {
            log.warn("Unsupported registry type for schema registry check: {}", registryType);
            return false;
        }
    }

    public boolean isConsulRunning() {
        return consulContainer != null && consulContainer.isRunning();
    }


    public boolean areEssentialContainersRunning(String... requiredTypes) {
        if (!initialized) {
            log.warn("Checking essential containers before TestContainerManager is initialized.");
            return false;
        }

        boolean allRunning = true;
        for (String type : requiredTypes) {
            boolean checkResult = true;
            switch (type.toLowerCase()) {
                case "kafka":
                    checkResult = isKafkaRunning();
                    if (!checkResult) log.warn("Essential check failed: Kafka not running.");
                    break;
                case "consul":
                    checkResult = isConsulRunning();
                    if (!checkResult) log.warn("Essential check failed: Consul not running.");
                    break;
                case "apicurio":
                    if (!"apicurio".equalsIgnoreCase(registryType)) {
                        log.warn("Essential check mismatch: Requested Apicurio, but type is '{}'.", registryType);
                        checkResult = false;
                    } else {
                        // Check if Apicurio container is running
                        checkResult = schemaRegistryContainer != null && schemaRegistryContainer.isRunning();
                        if (!checkResult) log.warn("Essential check failed: Apicurio registry container not running.");
                    }
                    break;
                case "glue":
                    if (!"glue".equalsIgnoreCase(registryType)) {
                        log.warn("Essential check mismatch: Requested Glue, but type is '{}'.", registryType);
                        checkResult = false;
                    } else {
                        // Check if Glue URL is configured (represents "running" external service)
                        checkResult = isSchemaRegistryRunning(); // Reuses the logic checking the property
                        if (!checkResult) log.warn("Essential check failed: Glue registry URL is not configured.");
                    }
                    break;
                case "moto":
                    if (!"moto".equalsIgnoreCase(registryType)) {
                        log.warn("Essential check mismatch: Requested Moto, but type is '{}'.", registryType);
                        checkResult = false;
                    } else {
                        // Check if MotoSchemaRegistry is initialized and running
                        checkResult = isSchemaRegistryRunning(); // Reuses the logic checking MotoSchemaRegistry
                        if (!checkResult) log.warn("Essential check failed: MotoSchemaRegistry is not initialized or not running.");
                    }
                    break;
                default:
                    log.warn("Unsupported container type in check: {}", type);
                    checkResult = false;
                    break;
            }
            if (!checkResult) {
                allRunning = false;
                break;
            }
        }
        return allRunning;
    }


    public synchronized void stopContainers() {
        log.info("Stopping containers managed by TestContainerManager...");
        // Stop only managed containers
        if (schemaRegistryContainer != null && schemaRegistryContainer.isRunning()) { // Only stops Apicurio
            schemaRegistryContainer.stop();
            log.info("Apicurio Registry container stopped.");
        }
        // --- No LocalStack container to stop ---
        if (kafkaContainer != null && kafkaContainer.isRunning()) {
            kafkaContainer.stop();
            log.info("Kafka container stopped.");
        }
        if (consulContainer != null && consulContainer.isRunning()) {
            consulContainer.stop();
            log.info("Consul container stopped.");
        }

        // Clear MotoSchemaRegistry reference
        if (motoSchemaRegistry != null) {
            // MotoSchemaRegistry doesn't have a stop method, but we should clear the reference
            motoSchemaRegistry = null;
            log.info("MotoSchemaRegistry reference cleared.");
        }

        // network.close(); // Consider if needed
        initialized = false;
        properties.clear();
        log.info("Managed containers stopped.");
    }
}
