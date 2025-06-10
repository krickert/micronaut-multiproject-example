package com.krickert.search.engine.grpc;

import com.google.protobuf.Empty;
import com.krickert.search.engine.service.ModuleRegistrationService;
import com.krickert.search.grpc.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * gRPC service implementation for module registration.
 * This service handles gRPC calls from modules wanting to register with the engine.
 */
@GrpcService
public class GrpcModuleRegistrationService extends ModuleRegistrationGrpc.ModuleRegistrationImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(GrpcModuleRegistrationService.class);
    
    private final ModuleRegistrationService registrationService;
    
    @Inject
    public GrpcModuleRegistrationService(ModuleRegistrationService registrationService) {
        this.registrationService = registrationService;
    }
    
    @Override
    public void registerModule(ModuleInfo request, StreamObserver<RegistrationStatus> responseObserver) {
        LOG.info("Received registration request for module: {}", request.getServiceId());
        
        registrationService.registerModule(request)
            .doOnSuccess(status -> LOG.info("Module {} registration status: {}", 
                request.getServiceId(), status.getSuccess()))
            .doOnError(error -> LOG.error("Error registering module {}", 
                request.getServiceId(), error))
            .subscribe(
                status -> {
                    responseObserver.onNext(status);
                    responseObserver.onCompleted();
                },
                error -> responseObserver.onError(error)
            );
    }
    
    @Override
    public void unregisterModule(ModuleId request, StreamObserver<UnregistrationStatus> responseObserver) {
        LOG.info("Received unregistration request for module: {}", request.getServiceId());
        
        registrationService.unregisterModule(request.getServiceId())
            .map(success -> UnregistrationStatus.newBuilder()
                .setSuccess(success)
                .setMessage(success ? "Module unregistered successfully" : "Module not found")
                .build())
            .subscribe(
                status -> {
                    responseObserver.onNext(status);
                    responseObserver.onCompleted();
                },
                error -> responseObserver.onError(error)
            );
    }
    
    @Override
    public void heartbeat(ModuleHeartbeat request, StreamObserver<HeartbeatAck> responseObserver) {
        LOG.debug("Received heartbeat from module: {}", request.getServiceId());
        
        registrationService.updateHeartbeat(request.getServiceId())
            .map(success -> HeartbeatAck.newBuilder()
                .setAcknowledged(success)
                .setServerTime(com.google.protobuf.Timestamp.newBuilder()
                    .setSeconds(System.currentTimeMillis() / 1000)
                    .build())
                .build())
            .subscribe(
                ack -> {
                    responseObserver.onNext(ack);
                    responseObserver.onCompleted();
                },
                error -> responseObserver.onError(error)
            );
    }
    
    @Override
    public void getModuleHealth(ModuleId request, StreamObserver<ModuleHealthStatus> responseObserver) {
        LOG.debug("Health check request for module: {}", request.getServiceId());
        
        registrationService.getModuleHealth(request.getServiceId())
            .subscribe(
                health -> {
                    responseObserver.onNext(health);
                    responseObserver.onCompleted();
                },
                error -> responseObserver.onError(error)
            );
    }
    
    @Override
    public void listModules(Empty request, StreamObserver<ModuleList> responseObserver) {
        LOG.debug("Received request to list all modules");
        
        registrationService.listRegisteredModules()
            .collectList()
            .map(modules -> ModuleList.newBuilder()
                .addAllModules(modules)
                .build())
            .subscribe(
                list -> {
                    responseObserver.onNext(list);
                    responseObserver.onCompleted();
                },
                error -> responseObserver.onError(error)
            );
    }
}