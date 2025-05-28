package com.krickert.yappy.engine.controller.admin;

import com.krickert.yappy.engine.controller.admin.dto.ConsulConfigResponse;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@MicronautTest
public class AdminSetupControllerTest {

    @Test
    void testGetCurrentConsulConfiguration_Success() throws IOException {
        Path testFilePath = Files.createTempFile("test-bootstrap", ".properties");
        Properties testProperties = new Properties();
        testProperties.setProperty("yappy.bootstrap.consul.host", "localhost");
        testProperties.setProperty("yappy.bootstrap.consul.port", "8500");
        testProperties.setProperty("yappy.bootstrap.consul.aclToken", "test-token");
        testProperties.setProperty("yappy.bootstrap.cluster.selectedName", "test-cluster");
        testProperties.store(Files.newOutputStream(testFilePath), null);

        AdminSetupController adminSetupController = mock(AdminSetupController.class);
        Mockito.doCallRealMethod().when(adminSetupController).getCurrentConsulConfiguration();
        when(adminSetupController.loadProperties()).thenReturn(testProperties);

        ConsulConfigResponse response = adminSetupController.getCurrentConsulConfiguration();

        assertNotNull(response);
        assertEquals("localhost", response.getHost());
        assertEquals("8500", response.getPort());
        assertEquals("test-token", response.getAclToken());
        assertEquals("test-cluster", response.getSelectedYappyClusterName());

        Files.deleteIfExists(testFilePath);
    }

    @Test
    void testGetCurrentConsulConfiguration_NoPropertiesFile() {
        AdminSetupController adminSetupController = mock(AdminSetupController.class);
        Mockito.doCallRealMethod().when(adminSetupController).getCurrentConsulConfiguration();
        when(adminSetupController.loadProperties()).thenReturn(new Properties());

        ConsulConfigResponse response = adminSetupController.getCurrentConsulConfiguration();

        assertNotNull(response);
        assertNull(response.getHost());
        assertNull(response.getPort());
        assertNull(response.getAclToken());
        assertNull(response.getSelectedYappyClusterName());
    }

    @Test
    void testGetCurrentConsulConfiguration_ErrorReadingFile() throws IOException {
        AdminSetupController adminSetupController = mock(AdminSetupController.class);
        Mockito.doCallRealMethod().when(adminSetupController).getCurrentConsulConfiguration();
        when(adminSetupController.loadProperties()).thenThrow(new IOException("Test exception"));

        ConsulConfigResponse response = adminSetupController.getCurrentConsulConfiguration();

        assertNotNull(response);
        assertNull(response.getHost());
        assertNull(response.getPort());
        assertNull(response.getAclToken());
        assertNull(response.getSelectedYappyClusterName());
    }
}