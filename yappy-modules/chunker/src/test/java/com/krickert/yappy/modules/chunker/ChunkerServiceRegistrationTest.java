package com.krickert.yappy.modules.chunker;

import com.google.protobuf.Empty;
import com.krickert.search.sdk.ServiceRegistrationData;
import io.grpc.stub.StreamObserver;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@MicronautTest
class ChunkerServiceRegistrationTest {

    @Inject
    ChunkerServiceGrpc chunkerService;

    @Test
    void testGetServiceRegistration() {
        // Given
        Empty request = Empty.getDefaultInstance();
        @SuppressWarnings("unchecked")
        StreamObserver<ServiceRegistrationData> responseObserver = mock(StreamObserver.class);
        ArgumentCaptor<ServiceRegistrationData> captor = ArgumentCaptor.forClass(ServiceRegistrationData.class);

        // When
        chunkerService.getServiceRegistration(request, responseObserver);

        // Then
        verify(responseObserver).onNext(captor.capture());
        verify(responseObserver).onCompleted();
        verify(responseObserver, never()).onError(any());

        ServiceRegistrationData registration = captor.getValue();
        assertNotNull(registration);
        assertEquals("chunker", registration.getModuleName());
        assertTrue(registration.hasJsonConfigSchema());
        
        // Verify schema contains expected fields
        String schema = registration.getJsonConfigSchema();
        assertNotNull(schema);
        assertTrue(schema.contains("\"$schema\""));
        assertTrue(schema.contains("http://json-schema.org/draft-07/schema#"));
        assertTrue(schema.contains("\"source_field\""));
        assertTrue(schema.contains("\"chunk_size\""));
        assertTrue(schema.contains("\"chunk_overlap\""));
        assertTrue(schema.contains("\"preserve_urls\""));
    }

    @Test
    void testGetServiceRegistrationReturnsValidJsonSchema() {
        // Given
        Empty request = Empty.getDefaultInstance();
        @SuppressWarnings("unchecked")
        StreamObserver<ServiceRegistrationData> responseObserver = mock(StreamObserver.class);
        ArgumentCaptor<ServiceRegistrationData> captor = ArgumentCaptor.forClass(ServiceRegistrationData.class);

        // When
        chunkerService.getServiceRegistration(request, responseObserver);

        // Then
        verify(responseObserver).onNext(captor.capture());
        ServiceRegistrationData registration = captor.getValue();
        
        // Verify the schema is from ChunkerOptions
        String expectedSchema = ChunkerOptions.getJsonV7Schema();
        assertEquals(expectedSchema, registration.getJsonConfigSchema());
    }
}