package com.krickert.search.engine.core.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RouteDataTest {

    @Test
    void testValidRouteDataCreation() {
        // Given valid parameters
        String targetPipeline = "document-processing";
        String targetStep = "chunker";
        String destination = "chunker-service";
        RouteData.TransportType transport = RouteData.TransportType.GRPC;
        String streamId = "stream-123";
        
        // When creating RouteData
        RouteData route = new RouteData(targetPipeline, targetStep, destination, transport, streamId);
        
        // Then all fields should be set correctly
        assertThat(route.targetPipelineName()).isEqualTo(targetPipeline);
        assertThat(route.targetStepName()).isEqualTo(targetStep);
        assertThat(route.destinationService()).isEqualTo(destination);
        assertThat(route.transportType()).isEqualTo(transport);
        assertThat(route.streamId()).isEqualTo(streamId);
    }
    
    @Test
    void testRouteDataWithNullPipeline() {
        // Given null pipeline (valid for same-pipeline routing)
        RouteData route = new RouteData(null, "chunker", "chunker-service", 
                RouteData.TransportType.GRPC, "stream-123");
        
        // Then pipeline should be null
        assertThat(route.targetPipelineName()).isNull();
    }
    
    @Test
    void testRouteDataValidation_NullTargetStep() {
        assertThatThrownBy(() -> 
            new RouteData("pipeline", null, "service", RouteData.TransportType.GRPC, "stream-123")
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("targetStepName cannot be null or blank");
    }
    
    @Test
    void testRouteDataValidation_BlankTargetStep() {
        assertThatThrownBy(() -> 
            new RouteData("pipeline", "  ", "service", RouteData.TransportType.GRPC, "stream-123")
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("targetStepName cannot be null or blank");
    }
    
    @Test
    void testRouteDataValidation_NullDestination() {
        assertThatThrownBy(() -> 
            new RouteData("pipeline", "step", null, RouteData.TransportType.GRPC, "stream-123")
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("destinationService cannot be null or blank");
    }
    
    @Test
    void testRouteDataValidation_NullTransportType() {
        assertThatThrownBy(() -> 
            new RouteData("pipeline", "step", "service", null, "stream-123")
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("transportType cannot be null");
    }
    
    @Test
    void testRouteDataValidation_NullStreamId() {
        assertThatThrownBy(() -> 
            new RouteData("pipeline", "step", "service", RouteData.TransportType.GRPC, null)
        )
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("streamId cannot be null or blank");
    }
    
    @Test
    void testTransportTypes() {
        // Verify all transport types are accessible
        assertThat(RouteData.TransportType.values())
            .containsExactly(
                RouteData.TransportType.GRPC,
                RouteData.TransportType.KAFKA,
                RouteData.TransportType.INTERNAL
            );
    }
}