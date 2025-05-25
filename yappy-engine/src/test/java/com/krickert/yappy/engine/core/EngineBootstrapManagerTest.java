package com.krickert.yappy.engine.core;

// Changed import
import io.micronaut.context.event.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class EngineBootstrapManagerTest {

    private EngineBootstrapManager manager;

    @TempDir
    Path tempDir;

    private Path testBootstrapFilePath;

    private Map<String, String> originalSystemProperties;
    private final List<String> propertiesToManage = Arrays.asList(
            "consul.client.host",
            "consul.client.port",
            "consul.client.registration.enabled",
            "consul.client.config.enabled",
            "micronaut.config-client.enabled",
            "yappy.consul.configured",
            "consul.client.acl-token"
    );

    // Property keys from EngineBootstrapManager/BootstrapConfigServiceImpl
    private static final String YAPPY_BOOTSTRAP_CONSUL_HOST = "yappy.bootstrap.consul.host";
    private static final String YAPPY_BOOTSTRAP_CONSUL_PORT = "yappy.bootstrap.consul.port";
    private static final String YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN = "yappy.bootstrap.consul.acl_token";

    @Mock
    private StartupEvent mockEvent; // Changed type here

    @BeforeEach
    void setUp() throws IOException {
        Path yappyDir = tempDir.resolve("custom-yappy-config-test");
        Files.createDirectories(yappyDir);
        testBootstrapFilePath = yappyDir.resolve("test-engine-bootstrap.properties");

        originalSystemProperties = new HashMap<>();
        for (String key : propertiesToManage) {
            originalSystemProperties.put(key, System.getProperty(key));
            System.clearProperty(key);
        }
        manager = new EngineBootstrapManager(testBootstrapFilePath.toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String key : propertiesToManage) {
            String originalValue = originalSystemProperties.get(key);
            if (originalValue != null) {
                System.setProperty(key, originalValue);
            } else {
                System.clearProperty(key);
            }
        }
        Files.deleteIfExists(testBootstrapFilePath);
        if (testBootstrapFilePath.getParent() != null && Files.exists(testBootstrapFilePath.getParent())) {
            Files.delete(testBootstrapFilePath.getParent());
        }
    }

    private void writeProperties(Properties props) throws IOException {
        try (OutputStream output = Files.newOutputStream(testBootstrapFilePath)) {
            props.store(output, null);
        }
    }

    private void assertSetupModeProperties() {
        assertNull(System.getProperty("yappy.consul.configured"), "yappy.consul.configured should not be set to true");
        assertNull(System.getProperty("consul.client.host"));
        assertNull(System.getProperty("consul.client.port"));
        assertNull(System.getProperty("consul.client.registration.enabled"));
        assertNull(System.getProperty("consul.client.config.enabled"));
        assertNull(System.getProperty("micronaut.config-client.enabled"));
        assertNull(System.getProperty("consul.client.acl-token"));
    }

    @Test
    void onApplicationEvent_bootstrapFileDoesNotExist_setupMode() {
        assertFalse(Files.exists(testBootstrapFilePath));
        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }

    @Test
    void onApplicationEvent_emptyBootstrapFile_setupMode() throws IOException {
        writeProperties(new Properties());
        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }

    @Test
    void onApplicationEvent_bootstrapFileLacksConsulHost_setupMode() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "1234");
        writeProperties(props);
        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }

    @Test
    void onApplicationEvent_bootstrapFileLacksConsulPort_setupMode() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "somehost");
        writeProperties(props);
        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }

    @Test
    void onApplicationEvent_bootstrapFileLacksAnyConsulDetails_setupMode() throws IOException {
        Properties props = new Properties();
        props.setProperty("some.other.property", "value");
        writeProperties(props);
        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }

    @Test
    void onApplicationEvent_validConsulHostAndPort_noAcl() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "connie.local");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "7777");
        writeProperties(props);

        manager.onApplicationEvent(mockEvent);

        assertEquals("true", System.getProperty("yappy.consul.configured"));
        assertEquals("connie.local", System.getProperty("consul.client.host"));
        assertEquals("7777", System.getProperty("consul.client.port"));
        assertEquals("true", System.getProperty("consul.client.registration.enabled"));
        assertEquals("true", System.getProperty("consul.client.config.enabled"));
        assertEquals("true", System.getProperty("micronaut.config-client.enabled"));
        assertNull(System.getProperty("consul.client.acl-token"));
    }

    @Test
    void onApplicationEvent_validConsulHostPortAndAcl() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "securehost.internal");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "8888");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN, "super-secret-token");
        writeProperties(props);

        manager.onApplicationEvent(mockEvent);

        assertEquals("true", System.getProperty("yappy.consul.configured"));
        assertEquals("securehost.internal", System.getProperty("consul.client.host"));
        assertEquals("8888", System.getProperty("consul.client.port"));
        assertEquals("true", System.getProperty("consul.client.registration.enabled"));
        assertEquals("true", System.getProperty("consul.client.config.enabled"));
        assertEquals("true", System.getProperty("micronaut.config-client.enabled"));
        assertEquals("super-secret-token", System.getProperty("consul.client.acl-token"));
    }

    @Test
    void onApplicationEvent_validConsulHostPortAndEmptyAcl() throws IOException {
        Properties props = new Properties();
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_HOST, "hostwithemptyacl.internal");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_PORT, "9999");
        props.setProperty(YAPPY_BOOTSTRAP_CONSUL_ACL_TOKEN, ""); // Empty ACL token
        writeProperties(props);

        manager.onApplicationEvent(mockEvent);

        assertEquals("true", System.getProperty("yappy.consul.configured"));
        assertEquals("hostwithemptyacl.internal", System.getProperty("consul.client.host"));
        assertEquals("9999", System.getProperty("consul.client.port"));
        assertEquals("true", System.getProperty("consul.client.registration.enabled"));
        assertEquals("true", System.getProperty("consul.client.config.enabled"));
        assertEquals("true", System.getProperty("micronaut.config-client.enabled"));
        assertNull(System.getProperty("consul.client.acl-token"), "ACL token should not be set if empty in props");
    }

    @Test
    void onApplicationEvent_IOExceptionReadingFile_setupMode() throws IOException {
        // Simulate IOException by making the path a directory
        Files.deleteIfExists(testBootstrapFilePath);
        Files.createDirectories(testBootstrapFilePath);

        manager.onApplicationEvent(mockEvent);
        assertSetupModeProperties();
    }
}