package com.krickert.search.schema.registry.service; // Or your chosen package for gRPC services

import com.google.protobuf.Empty;
import com.google.protobuf.Timestamp;
import com.krickert.search.schema.registry.*; // Your generated gRPC classes
import com.krickert.search.schema.registry.delegate.ConsulSchemaRegistryDelegate;
import com.krickert.search.schema.registry.exception.SchemaNotFoundException;
import com.networknt.schema.ValidationMessage;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
@GrpcService
public class SchemaRegistryServiceImpl extends SchemaRegistryServiceGrpc.SchemaRegistryServiceImplBase {

    private static final Logger log = LoggerFactory.getLogger(SchemaRegistryServiceImpl.class);
    private final ConsulSchemaRegistryDelegate delegate;

    @Inject
    public SchemaRegistryServiceImpl(ConsulSchemaRegistryDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public void registerSchema(RegisterSchemaRequest request, StreamObserver<RegisterSchemaResponse> responseObserver) {
        log.info("gRPC RegisterSchema request for ID: {}", request.getSchemaId());
        //noinspection ReactorTransformationOnMonoVoid
        delegate.saveSchema(request.getSchemaId(), request.getSchemaContent())
                .subscribe(
                        (Void v) -> { /* No-op for Mono<Void>'s "success" value */ },
                        error -> { // onError
                            log.error("Error registering schema ID '{}': {}", request.getSchemaId(), error.getMessage());
                            RegisterSchemaResponse.Builder responseBuilder = RegisterSchemaResponse.newBuilder()
                                    .setSchemaId(request.getSchemaId())
                                    .setSuccess(false)
                                    .setTimestamp(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build());

                            Status status;
                            if (error instanceof IllegalArgumentException) {
                                status = Status.INVALID_ARGUMENT.withDescription(error.getMessage());
                                responseBuilder.addValidationErrors(error.getMessage());
                            } else if (error instanceof SchemaNotFoundException) { // Should not happen for register typically, but for completeness
                                status = Status.NOT_FOUND.withDescription(error.getMessage());
                            } else {
                                status = Status.INTERNAL.withDescription("Internal error: " + error.getMessage());
                            }
                            responseObserver.onNext(responseBuilder.build()); // Send error details if any
                            responseObserver.onError(status.asRuntimeException());
                        },
                        () -> { // onComplete (Runnable)
                            RegisterSchemaResponse response = RegisterSchemaResponse.newBuilder()
                                    .setSchemaId(request.getSchemaId())
                                    .setSuccess(true)
                                    .setTimestamp(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build())
                                    .build();
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        }
                );
    }

    @Override
    public void getSchema(GetSchemaRequest request, StreamObserver<GetSchemaResponse> responseObserver) {
        log.info("gRPC GetSchema request for ID: {}", request.getSchemaId());
        delegate.getSchemaContent(request.getSchemaId())
                .subscribe(
                        schemaContent -> {
                            SchemaInfo schemaInfo = SchemaInfo.newBuilder()
                                    .setSchemaId(request.getSchemaId())
                                    .setSchemaContent(schemaContent)
                                    // TODO: Add created_at, updated_at, description, metadata if/when delegate provides them
                                    .setCreatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build()) // Placeholder
                                    .setUpdatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build()) // Placeholder
                                    .build();
                            GetSchemaResponse response = GetSchemaResponse.newBuilder().setSchemaInfo(schemaInfo).build();
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        },
                        error -> {
                            log.error("Error getting schema ID '{}': {}", request.getSchemaId(), error.getMessage());
                            Status status;
                            if (error instanceof SchemaNotFoundException) {
                                status = Status.NOT_FOUND.withDescription(error.getMessage());
                            } else if (error instanceof IllegalArgumentException) {
                                status = Status.INVALID_ARGUMENT.withDescription(error.getMessage());
                            } else {
                                status = Status.INTERNAL.withDescription("Internal error: " + error.getMessage());
                            }
                            responseObserver.onError(status.asRuntimeException());
                        }
                );
    }

    @Override
    public void deleteSchema(DeleteSchemaRequest request, StreamObserver<DeleteSchemaResponse> responseObserver) {
        log.info("gRPC DeleteSchema request for ID: {}", request.getSchemaId());
        //noinspection ReactorTransformationOnMonoVoid
        delegate.deleteSchema(request.getSchemaId())
                .subscribe(
                        (Void v) -> { /* No-op for Mono<Void>'s "success" value */ },
                        error -> { // onError
                            log.error("Error deleting schema ID '{}': {}", request.getSchemaId(), error.getMessage());
                            Status status;
                            if (error instanceof SchemaNotFoundException) {
                                status = Status.NOT_FOUND.withDescription(error.getMessage());
                            } else if (error instanceof IllegalArgumentException) {
                                status = Status.INVALID_ARGUMENT.withDescription(error.getMessage());
                            } else {
                                status = Status.INTERNAL.withDescription("Internal error: " + error.getMessage());
                            }
                            responseObserver.onError(status.asRuntimeException());
                        },
                        () -> { // onComplete (Runnable)
                            DeleteSchemaResponse response = DeleteSchemaResponse.newBuilder()
                                    .setAcknowledgement(Empty.newBuilder().build())
                                    .build();
                            responseObserver.onNext(response);
                            responseObserver.onCompleted();
                        }
                );
    }


    @Override
    public void listSchemas(ListSchemasRequest request, StreamObserver<ListSchemasResponse> responseObserver) {
        log.info("gRPC ListSchemas request. Filter: '{}'", request.getIdFilter()); // PageSize and PageToken removed from log
        delegate.listSchemaIds() // Assuming this returns Mono<List<String>> of all matching IDs
                .subscribe(
                        schemaIds -> {
                            ListSchemasResponse.Builder responseBuilder = ListSchemasResponse.newBuilder();
                            for (String id : schemaIds) {
                                responseBuilder.addSchemas(SchemaInfo.newBuilder().setSchemaId(id)
                                        .setCreatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build()) // Placeholder
                                        .setUpdatedAt(Timestamp.newBuilder().setSeconds(System.currentTimeMillis() / 1000L).build()) // Placeholder
                                        .build());
                            }
                            // No next_page_token to set
                            responseObserver.onNext(responseBuilder.build());
                            responseObserver.onCompleted();
                        },
                        error -> {
                            log.error("Error listing schemas: {}", error.getMessage(), error);
                            responseObserver.onError(Status.INTERNAL
                                    .withDescription("Error listing schemas: " + error.getMessage())
                                    .withCause(error)
                                    .asRuntimeException());
                        }
                );
    }

    @Override
    public void validateSchemaContent(ValidateSchemaContentRequest request, StreamObserver<ValidateSchemaContentResponse> responseObserver) {
        log.info("gRPC ValidateSchemaContent request");
        delegate.validateSchemaSyntax(request.getSchemaContent())
                .subscribe(
                        validationMessages -> {
                            ValidateSchemaContentResponse.Builder responseBuilder = ValidateSchemaContentResponse.newBuilder();
                            if (validationMessages.isEmpty()) {
                                responseBuilder.setIsValid(true);
                            } else {
                                responseBuilder.setIsValid(false);
                                for (ValidationMessage vm : validationMessages) {
                                    responseBuilder.addValidationErrors(vm.getMessage());
                                }
                            }
                            responseObserver.onNext(responseBuilder.build());
                            responseObserver.onCompleted();
                        },
                        error -> { // Should ideally not happen if validateSchemaSyntax catches its own errors and returns Set
                            log.error("Unexpected error during validateSchemaContent: {}", error.getMessage());
                            responseObserver.onError(Status.INTERNAL.withDescription("Internal error validating schema: " + error.getMessage()).asRuntimeException());
                        }
                );
    }
}