package com.krickert.yappy.engine.service;

import com.krickert.search.config.consul.service.ConsulBusinessOperationsService;
import com.krickert.yappy.bootstrap.api.*;
import com.krickert.yappy.bootstrap.service.BootstrapConfigServiceImpl;
import com.krickert.yappy.engine.controller.admin.dto.*;
import io.grpc.stub.StreamObserver;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Singleton
public class BootstrapAdminServiceImpl implements BootstrapAdminService {
    
    private static final Logger LOG = LoggerFactory.getLogger(BootstrapAdminServiceImpl.class);
    
    private final BootstrapConfigServiceImpl bootstrapConfigService;
    private final ConsulBusinessOperationsService consulBusinessOperationsService;
    
    @Inject
    public BootstrapAdminServiceImpl(
            BootstrapConfigServiceImpl bootstrapConfigService,
            ConsulBusinessOperationsService consulBusinessOperationsService) {
        this.bootstrapConfigService = bootstrapConfigService;
        this.consulBusinessOperationsService = consulBusinessOperationsService;
    }
    
    @Override
    public Mono<ConsulConfigResponse> getCurrentConsulConfiguration() {
        return Mono.create(sink -> {
            bootstrapConfigService.getConsulConfiguration(
                com.google.protobuf.Empty.getDefaultInstance(),
                new StreamObserver<ConsulConfigDetails>() {
                    @Override
                    public void onNext(ConsulConfigDetails details) {
                        ConsulConfigResponse response = new ConsulConfigResponse();
                        if (!details.getHost().isEmpty()) {
                            response.setHost(details.getHost());
                        }
                        if (details.getPort() != 0) {
                            response.setPort(String.valueOf(details.getPort()));
                        }
                        if (details.hasAclToken() && !details.getAclToken().isEmpty()) {
                            response.setAclToken(details.getAclToken());
                        }
                        if (details.hasSelectedYappyClusterName() && !details.getSelectedYappyClusterName().isEmpty()) {
                            response.setSelectedYappyClusterName(details.getSelectedYappyClusterName());
                        }
                        sink.success(response);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        LOG.error("Error getting consul configuration", t);
                        sink.success(new ConsulConfigResponse()); // Return empty response on error
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already completed in onNext
                    }
                }
            );
        });
    }
    
    @Override
    public Mono<SetConsulConfigResponse> setConsulConfiguration(SetConsulConfigRequest request) {
        return Mono.create(sink -> {
            ConsulConfigDetails.Builder detailsBuilder = ConsulConfigDetails.newBuilder()
                .setHost(request.getHost())
                .setPort(request.getPort());
            
            if (request.getAclToken() != null && !request.getAclToken().trim().isEmpty()) {
                detailsBuilder.setAclToken(request.getAclToken());
            }
            
            bootstrapConfigService.setConsulConfiguration(
                detailsBuilder.build(),
                new StreamObserver<ConsulConnectionStatus>() {
                    @Override
                    public void onNext(ConsulConnectionStatus status) {
                        SetConsulConfigResponse response = new SetConsulConfigResponse();
                        response.setSuccess(status.getSuccess());
                        response.setMessage(status.getMessage());
                        
                        if (status.hasCurrentConfig()) {
                            ConsulConfigResponse currentConfig = new ConsulConfigResponse();
                            ConsulConfigDetails current = status.getCurrentConfig();
                            if (!current.getHost().isEmpty()) {
                                currentConfig.setHost(current.getHost());
                            }
                            if (current.getPort() != 0) {
                                currentConfig.setPort(String.valueOf(current.getPort()));
                            }
                            if (current.hasAclToken() && !current.getAclToken().isEmpty()) {
                                currentConfig.setAclToken(current.getAclToken());
                            }
                            if (current.hasSelectedYappyClusterName() && !current.getSelectedYappyClusterName().isEmpty()) {
                                currentConfig.setSelectedYappyClusterName(current.getSelectedYappyClusterName());
                            }
                            response.setCurrentConfig(currentConfig);
                        }
                        
                        sink.success(response);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        LOG.error("Error setting consul configuration", t);
                        SetConsulConfigResponse response = new SetConsulConfigResponse();
                        response.setSuccess(false);
                        response.setMessage("Error updating Consul configuration: " + t.getMessage());
                        sink.success(response);
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already completed in onNext
                    }
                }
            );
        });
    }
    
    @Override
    public Mono<List<YappyClusterInfo>> listAvailableYappyClusters() {
        return Mono.create(sink -> {
            bootstrapConfigService.listAvailableClusters(
                com.google.protobuf.Empty.getDefaultInstance(),
                new StreamObserver<ClusterList>() {
                    @Override
                    public void onNext(ClusterList clusterList) {
                        List<YappyClusterInfo> clusters = new ArrayList<>();
                        for (ClusterInfo clusterInfo : clusterList.getClustersList()) {
                            YappyClusterInfo info = new YappyClusterInfo();
                            info.setClusterName(clusterInfo.getClusterName());
                            info.setClusterId(clusterInfo.getClusterId());
                            info.setStatus(clusterInfo.getStatus());
                            clusters.add(info);
                        }
                        sink.success(clusters);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        LOG.error("Error listing available clusters", t);
                        sink.success(new ArrayList<>()); // Return empty list on error
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already completed in onNext
                    }
                }
            );
        });
    }
    
    @Override
    public Mono<CreateClusterResponse> createNewYappyCluster(CreateClusterRequest request) {
        // First check if cluster already exists
        return consulBusinessOperationsService.getPipelineClusterConfig(request.getClusterName())
            .flatMap(existingConfig -> {
                if (existingConfig.isPresent()) {
                    String message = "Failed to store cluster configuration - cluster '" + request.getClusterName() + "' already exists. Use a different name or delete the existing cluster first.";
                    LOG.warn(message);
                    return Mono.just(new CreateClusterResponse(false, message, request.getClusterName(), ""));
                }
                
                return Mono.create(sink -> {
                    NewClusterDetails.Builder detailsBuilder = NewClusterDetails.newBuilder()
                        .setClusterName(request.getClusterName());
                    
                    if (request.getFirstPipelineName() != null && !request.getFirstPipelineName().trim().isEmpty()) {
                        detailsBuilder.setFirstPipelineName(request.getFirstPipelineName());
                    }
                    
                    if (request.getInitialModules() != null && !request.getInitialModules().isEmpty()) {
                        for (PipelineModuleInput moduleInput : request.getInitialModules()) {
                            InitialModuleConfig moduleConfig = InitialModuleConfig.newBuilder()
                                .setImplementationId(moduleInput.getImplementationId())
                                .build();
                            detailsBuilder.addInitialModulesForFirstPipeline(moduleConfig);
                        }
                    }
                    
                    bootstrapConfigService.createNewCluster(
                        detailsBuilder.build(),
                        new StreamObserver<ClusterCreationStatus>() {
                            @Override
                            public void onNext(ClusterCreationStatus status) {
                                CreateClusterResponse response = new CreateClusterResponse();
                                response.setSuccess(status.getSuccess());
                                response.setMessage(status.getMessage());
                                response.setClusterName(status.getClusterName());
                                if (!status.getSeededConfigPath().isEmpty()) {
                                    response.setSeededConfigPath(status.getSeededConfigPath());
                                }
                                sink.success(response);
                            }
                            
                            @Override
                            public void onError(Throwable t) {
                                LOG.error("Error creating new cluster", t);
                                CreateClusterResponse response = new CreateClusterResponse();
                                response.setSuccess(false);
                                response.setMessage("Error creating cluster: " + t.getMessage());
                                response.setClusterName(request.getClusterName());
                                sink.success(response);
                            }
                            
                            @Override
                            public void onCompleted() {
                                // Response already completed in onNext
                            }
                        }
                    );
                });
            });
    }
    
    @Override
    public Mono<SelectClusterResponse> selectActiveYappyCluster(SelectClusterRequest request) {
        return Mono.create(sink -> {
            ClusterSelection selection = ClusterSelection.newBuilder()
                .setClusterName(request.getClusterName())
                .build();
            
            bootstrapConfigService.selectExistingCluster(
                selection,
                new StreamObserver<OperationStatus>() {
                    @Override
                    public void onNext(OperationStatus status) {
                        SelectClusterResponse response = new SelectClusterResponse();
                        response.setSuccess(status.getSuccess());
                        response.setMessage(status.getMessage());
                        sink.success(response);
                    }
                    
                    @Override
                    public void onError(Throwable t) {
                        LOG.error("Error selecting cluster", t);
                        SelectClusterResponse response = new SelectClusterResponse();
                        response.setSuccess(false);
                        response.setMessage("Error selecting cluster: " + t.getMessage());
                        sink.success(response);
                    }
                    
                    @Override
                    public void onCompleted() {
                        // Response already completed in onNext
                    }
                }
            );
        });
    }
}