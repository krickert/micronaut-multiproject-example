package com.krickert.yappy.modules.connector.test.server;

import com.krickert.search.engine.ConnectorEngineGrpc;
import com.krickert.search.engine.ConnectorRequest;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assertions;

import java.util.UUID;

@MicronautTest
public class ConnectorTestServiceTest {

    @Inject
    ConnectorEngineGrpc.ConnectorEngineBlockingStub blockingStub;

    @Test
    void testProcessConnectorDoc_Success() {
        // Create a request with result_response=success
        String docId = UUID.randomUUID().toString();
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier("test-source")
                .setDocument(PipeDoc.newBuilder().setId(docId).build())
                .putInitialContextParams(ConnectorTestHelper.RESULT_RESPONSE_KEY, ConnectorTestHelper.SUCCESS_VALUE)
                .build();

        // Call the service
        ConnectorResponse response = blockingStub.processConnectorDoc(request);

        // Verify the response
        Assertions.assertTrue(response.getAccepted(), "Request should be accepted");
        Assertions.assertFalse(response.getStreamId().isEmpty(), "Stream ID should not be empty");
        Assertions.assertTrue(response.getMessage().contains("accepted"), "Message should indicate acceptance");
    }

    @Test
    void testProcessConnectorDoc_Failure() {
        // Create a request with result_response=fail
        String docId = UUID.randomUUID().toString();
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier("test-source")
                .setDocument(PipeDoc.newBuilder().setId(docId).build())
                .putInitialContextParams(ConnectorTestHelper.RESULT_RESPONSE_KEY, ConnectorTestHelper.FAIL_VALUE)
                .build();

        // Call the service
        ConnectorResponse response = blockingStub.processConnectorDoc(request);

        // Verify the response
        Assertions.assertFalse(response.getAccepted(), "Request should be rejected");
        Assertions.assertFalse(response.getStreamId().isEmpty(), "Stream ID should not be empty");
        Assertions.assertTrue(response.getMessage().contains("rejected"), "Message should indicate rejection");
    }

    @Test
    void testProcessConnectorDoc_DefaultSuccess() {
        // Create a request without result_response
        String docId = UUID.randomUUID().toString();
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier("test-source")
                .setDocument(PipeDoc.newBuilder().setId(docId).build())
                .build();

        // Call the service
        ConnectorResponse response = blockingStub.processConnectorDoc(request);

        // Verify the response
        Assertions.assertTrue(response.getAccepted(), "Request should be accepted by default");
        Assertions.assertFalse(response.getStreamId().isEmpty(), "Stream ID should not be empty");
        Assertions.assertTrue(response.getMessage().contains("accepted"), "Message should indicate acceptance");
    }

    @Test
    void testProcessConnectorDoc_WithSuggestedStreamId() {
        // Create a request with a suggested stream ID
        String docId = UUID.randomUUID().toString();
        String suggestedStreamId = UUID.randomUUID().toString();
        ConnectorRequest request = ConnectorRequest.newBuilder()
                .setSourceIdentifier("test-source")
                .setDocument(PipeDoc.newBuilder().setId(docId).build())
                .setSuggestedStreamId(suggestedStreamId)
                .build();

        // Call the service
        ConnectorResponse response = blockingStub.processConnectorDoc(request);

        // Verify the response
        Assertions.assertTrue(response.getAccepted(), "Request should be accepted");
        Assertions.assertEquals(suggestedStreamId, response.getStreamId(), "Stream ID should match suggested ID");
    }

    @Factory
    static class Clients {
        @Singleton
        ConnectorEngineGrpc.ConnectorEngineBlockingStub blockingStub(
                @GrpcChannel(GrpcServerChannel.NAME) ManagedChannel channel) {
            return ConnectorEngineGrpc.newBlockingStub(channel);
        }
    }
}