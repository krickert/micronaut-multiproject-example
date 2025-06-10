package com.krickert.search.engine.grpc;

import com.ecwid.consul.v1.ConsulClient;
import io.micronaut.context.ApplicationContext;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@MicronautTest
class EngineGrpcModuleTest {
    
    @MockBean(ConsulClient.class)
    ConsulClient mockConsulClient() {
        return mock(ConsulClient.class);
    }
    
    @Inject
    ApplicationContext applicationContext;
    
    @Test
    void testModuleRegistrationServiceBeanExists() {
        assertTrue(applicationContext.containsBean(ModuleRegistrationServiceImpl.class),
                "ModuleRegistrationServiceImpl should be available as a bean");
    }
    
    @Test
    void testConsulClientFactoryBeanExists() {
        assertTrue(applicationContext.containsBean(ConsulClientFactory.class),
                "ConsulClientFactory should be available as a bean");
    }
}