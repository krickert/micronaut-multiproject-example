package com.krickert.yappy.modules.wikipediaconnector;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.yappy.modules.wikipediaconnector.config.WikipediaConnectorConfig;
import com.krickert.yappy.modules.wikipediaconnector.service.WikipediaService;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.dkpro.jwpl.api.Wikipedia;
import org.dkpro.jwpl.api.exception.WikiApiException;
import org.dkpro.jwpl.api.exception.WikiInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wikipedia connector service that implements the PipeStepProcessor interface.
 * This service connects to a Wikipedia database or dump, retrieves articles,
 * and prepares them for processing.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.wikipedia-connector.enabled", value = "true", defaultValue = "true")
public class WikipediaConnectorService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaConnectorService.class);

    @Inject
    private WikipediaService wikipediaService;

    /**
     * Processes a request to connect to a Wikipedia database and process its contents.
     *
     * @param request          the process request
     * @param responseObserver the response observer
     */
    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument();

        LOG.info("WikipediaConnectorService received request for pipeline: {}, step: {}",
                metadata.getPipelineName(), metadata.getPipeStepName());

        String streamId = metadata.getStreamId();
        String docId = document.getId();

        LOG.debug("Stream ID: {}, Document ID: {}", streamId, docId);

        // Extract configuration
        WikipediaConnectorConfig wikipediaConfig = extractConfig(config.getCustomJsonConfig());
        String logPrefix = wikipediaConfig.logPrefix() != null ? wikipediaConfig.logPrefix() : "";

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        responseBuilder.setSuccess(true);

        try {
            // Create a Wikipedia API instance
            Wikipedia wikipedia = wikipediaService.createWikipediaApi(wikipediaConfig);

            // Determine what to retrieve based on the configuration
            PipeDoc outputDoc;

            if (wikipediaConfig.titleFilter() != null && !wikipediaConfig.titleFilter().isEmpty()) {
                // Retrieve a specific article by title
                LOG.info("{}Retrieving Wikipedia article with title: {}", logPrefix, wikipediaConfig.titleFilter());
                outputDoc = wikipediaService.getArticleByTitle(wikipedia, wikipediaConfig.titleFilter());
            } else if (wikipediaConfig.categoryFilter() != null && !wikipediaConfig.categoryFilter().isEmpty()) {
                // Retrieve articles by category
                LOG.info("{}Retrieving Wikipedia articles from category: {}", logPrefix, wikipediaConfig.categoryFilter());
                outputDoc = wikipediaService.getArticlesByCategory(
                        wikipedia, 
                        wikipediaConfig.categoryFilter(), 
                        wikipediaConfig.maxArticles(), 
                        wikipediaConfig.includeCategories())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new WikiApiException("No articles found in category: " + wikipediaConfig.categoryFilter()));
            } else {
                // No specific article or category specified, return an error
                throw new IllegalArgumentException("No title or category filter specified in the configuration");
            }

            // Add the document to the response
            responseBuilder.setOutputDoc(outputDoc);

            String logMessage = String.format("%sWikipediaConnectorService successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                    logPrefix,
                    metadata.getPipeStepName(),
                    metadata.getPipelineName(),
                    streamId,
                    docId);
            responseBuilder.addProcessorLogs(logMessage);
            LOG.info("{}Sending response for stream ID: {}", logPrefix, streamId);

            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (WikiInitializationException e) {
            LOG.error("{}Error initializing Wikipedia API: {}", logPrefix, e.getMessage(), e);
            responseBuilder.setSuccess(false);
            responseBuilder.addProcessorLogs("Error initializing Wikipedia API: " + e.getMessage());
            responseBuilder.setOutputDoc(document); // Return the original document on error
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (WikiApiException e) {
            LOG.error("{}Error retrieving Wikipedia article: {}", logPrefix, e.getMessage(), e);
            responseBuilder.setSuccess(false);
            responseBuilder.addProcessorLogs("Error retrieving Wikipedia article: " + e.getMessage());
            responseBuilder.setOutputDoc(document); // Return the original document on error
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            LOG.error("{}Error in WikipediaConnectorService: {}", logPrefix, e.getMessage(), e);
            responseBuilder.setSuccess(false);
            responseBuilder.addProcessorLogs("Error in WikipediaConnectorService: " + e.getMessage());
            responseBuilder.setOutputDoc(document); // Return the original document on error
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();
        }
    }

    /**
     * Extracts the Wikipedia connector configuration from the custom JSON configuration.
     *
     * @param customConfig the custom JSON configuration
     * @return the Wikipedia connector configuration
     */
    private WikipediaConnectorConfig extractConfig(Struct customConfig) {
        WikipediaConnectorConfig.WikipediaConnectorConfigBuilder builder = WikipediaConnectorConfig.defaults();

        if (customConfig == null) {
            LOG.info("No custom configuration provided, using defaults");
            return builder.build();
        }

        // Extract database connection settings
        if (customConfig.containsFields("host")) {
            Value value = customConfig.getFieldsOrDefault("host", null);
            if (value != null && value.hasStringValue()) {
                builder.host(value.getStringValue());
            }
        }

        if (customConfig.containsFields("port")) {
            Value value = customConfig.getFieldsOrDefault("port", null);
            if (value != null && value.hasNumberValue()) {
                builder.port((int) value.getNumberValue());
            }
        }

        if (customConfig.containsFields("database")) {
            Value value = customConfig.getFieldsOrDefault("database", null);
            if (value != null && value.hasStringValue()) {
                builder.database(value.getStringValue());
            }
        }

        if (customConfig.containsFields("user")) {
            Value value = customConfig.getFieldsOrDefault("user", null);
            if (value != null && value.hasStringValue()) {
                builder.user(value.getStringValue());
            }
        }

        if (customConfig.containsFields("password")) {
            Value value = customConfig.getFieldsOrDefault("password", null);
            if (value != null && value.hasStringValue()) {
                builder.password(value.getStringValue());
            }
        }

        // Extract Wikipedia dump settings
        if (customConfig.containsFields("dumpPath")) {
            Value value = customConfig.getFieldsOrDefault("dumpPath", null);
            if (value != null && value.hasStringValue()) {
                builder.dumpPath(value.getStringValue());
            }
        }

        if (customConfig.containsFields("language")) {
            Value value = customConfig.getFieldsOrDefault("language", null);
            if (value != null && value.hasStringValue()) {
                builder.language(value.getStringValue());
            }
        }

        // Extract article filtering
        if (customConfig.containsFields("categoryFilter")) {
            Value value = customConfig.getFieldsOrDefault("categoryFilter", null);
            if (value != null && value.hasStringValue()) {
                builder.categoryFilter(value.getStringValue());
            }
        }

        if (customConfig.containsFields("titleFilter")) {
            Value value = customConfig.getFieldsOrDefault("titleFilter", null);
            if (value != null && value.hasStringValue()) {
                builder.titleFilter(value.getStringValue());
            }
        }

        if (customConfig.containsFields("maxArticles")) {
            Value value = customConfig.getFieldsOrDefault("maxArticles", null);
            if (value != null && value.hasNumberValue()) {
                builder.maxArticles((int) value.getNumberValue());
            }
        }

        // Extract processing options
        if (customConfig.containsFields("includeCategories")) {
            Value value = customConfig.getFieldsOrDefault("includeCategories", null);
            if (value != null && value.hasBoolValue()) {
                builder.includeCategories(value.getBoolValue());
            }
        }

        if (customConfig.containsFields("includeDiscussions")) {
            Value value = customConfig.getFieldsOrDefault("includeDiscussions", null);
            if (value != null && value.hasBoolValue()) {
                builder.includeDiscussions(value.getBoolValue());
            }
        }

        if (customConfig.containsFields("includeRedirects")) {
            Value value = customConfig.getFieldsOrDefault("includeRedirects", null);
            if (value != null && value.hasBoolValue()) {
                builder.includeRedirects(value.getBoolValue());
            }
        }

        // Extract Kafka settings
        if (customConfig.containsFields("kafkaTopic")) {
            Value value = customConfig.getFieldsOrDefault("kafkaTopic", null);
            if (value != null && value.hasStringValue()) {
                builder.kafkaTopic(value.getStringValue());
            }
        }

        // Extract logging options
        if (customConfig.containsFields("logPrefix")) {
            Value value = customConfig.getFieldsOrDefault("logPrefix", null);
            if (value != null && value.hasStringValue()) {
                builder.logPrefix(value.getStringValue());
            }
        }

        return builder.build();
    }
}
