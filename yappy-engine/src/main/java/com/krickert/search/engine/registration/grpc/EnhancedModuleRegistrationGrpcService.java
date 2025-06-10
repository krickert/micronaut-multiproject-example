package com.krickert.search.engine.registration.grpc;

import com.krickert.search.engine.registration.ModuleRegistrationService;
import com.krickert.yappy.registration.api.RegisterModuleRequest;
import com.krickert.yappy.registration.api.RegisterModuleResponse;
import com.krickert.yappy.registration.api.YappyModuleRegistrationServiceGrpc;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enhanced gRPC service implementation for module registration with validation.
 * This implementation is marked as @Primary to override the old implementation.
 */
@Singleton
@Primary
@GrpcService
@Requires(property = "yappy.engine.registration.enhanced", value = "true", defaultValue = "true")
public class EnhancedModuleRegistrationGrpcService extends YappyModuleRegistrationServiceGrpc.YappyModuleRegistrationServiceImplBase {
    
    private static final Logger LOG = LoggerFactory.getLogger(EnhancedModuleRegistrationGrpcService.class);
    
    private final ModuleRegistrationService registrationService;
    
    @Inject
    public EnhancedModuleRegistrationGrpcService(ModuleRegistrationService registrationService) {
        this.registrationService = registrationService;
    }
    
    @Override
    public void registerModule(RegisterModuleRequest request, StreamObserver<RegisterModuleResponse> responseObserver) {
        LOG.info("Received enhanced RegisterModule request for implementationId: {}, serviceName: {}",
                request.getImplementationId(), request.getInstanceServiceName());
        
        registrationService.registerModule(request)
                .subscribe(
                        response -> {
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                            
                            if (response.getSuccess()) {
                                LOG.info("Module registration completed successfully for {}",
                                        request.getImplementationId());
                            } else {
                                LOG.warn("Module registration failed for {}: {}",
                                        request.getImplementationId(), response.getMessage());
                            }
                        },
                        error -> {
                            LOG.error("Module registration error for {}", 
                                    request.getImplementationId(), error);
                            
                            RegisterModuleResponse errorResponse = RegisterModuleResponse.newBuilder()
                                    .setSuccess(false)
                                    .setMessage("Internal error: " + error.getMessage())
                                    .build();
                            
                            responseObserver.onNext(errorResponse);
                            responseObserver.onCompleted();
                        }
                );
    }
}