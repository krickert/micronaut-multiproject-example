package @@BASE_PACKAGE@@;

import com.krickert.search.schema.registry.SchemaRegistryServiceGrpc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import io.grpc.ManagedChannel;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import io.micronaut.grpc.annotation.GrpcChannel;
import io.micronaut.grpc.server.GrpcServerChannel;

@Factory
public class Clients {

    @Bean
    PipeStepProcessorGrpc.PipeStepProcessorBlockingStub  pipeStepProcessorBlockingStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return PipeStepProcessorGrpc.newBlockingStub(
                channel
        );
    }

    @Bean
    PipeStepProcessorGrpc.PipeStepProcessorStub serviceStub(
            @GrpcChannel(GrpcServerChannel.NAME)
            ManagedChannel channel) {
        return PipeStepProcessorGrpc.newStub(
                channel
        );
    }
}