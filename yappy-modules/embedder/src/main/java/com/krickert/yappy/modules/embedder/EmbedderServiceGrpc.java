package com.krickert.yappy.modules.embedder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.model.*;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * gRPC service implementation for the embedder service.
 * This service processes documents and generates embeddings for chunks and document fields.
 */
@Singleton
@GrpcService
@Requires(property = "grpc.services.embedder.enabled", value = "true", defaultValue = "true")
public class EmbedderServiceGrpc extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger log = LoggerFactory.getLogger(EmbedderServiceGrpc.class);

    private final ObjectMapper objectMapper;
    private final Map<String, Vectorizer> vectorizers;

    @Inject
    public EmbedderServiceGrpc(ObjectMapper objectMapper, List<Vectorizer> vectorizers) {
        this.objectMapper = objectMapper;
        this.vectorizers = vectorizers.stream()
                .collect(Collectors.toMap(Vectorizer::getModelId, v -> v));
        log.info("Initialized EmbedderServiceGrpc with {} vectorizers: {}", 
                vectorizers.size(), 
                vectorizers.stream().map(Vectorizer::getModelId).collect(Collectors.joining(", ")));
    }

    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        PipeDoc inputDoc = request.getDocument();
        ProcessConfiguration config = request.getConfig();
        ServiceMetadata metadata = request.getMetadata();
        String streamId = metadata.getStreamId();
        String pipeStepName = metadata.getPipeStepName();

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        PipeDoc.Builder outputDocBuilder = inputDoc.toBuilder();

        try {
            log.info("Processing document ID: {} for step: {} in stream: {}", inputDoc.getId(), pipeStepName, streamId);

            // Parse configuration
            Struct customJsonConfig = config.getCustomJsonConfig();
            EmbedderOptions embedderOptions;
            if (customJsonConfig != null && customJsonConfig.getFieldsCount() > 0) {
                embedderOptions = objectMapper.readValue(
                        JsonFormat.printer().print(customJsonConfig),
                        EmbedderOptions.class
                );
            } else {
                log.warn("No custom JSON config provided for EmbedderService. Using defaults. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
                embedderOptions = new EmbedderOptions();
            }

            // Validate that we have the required vectorizers
            List<EmbeddingModel> availableModels = new ArrayList<>();
            for (EmbeddingModel model : embedderOptions.embeddingModels()) {
                String modelId = model.name();
                if (vectorizers.containsKey(modelId)) {
                    availableModels.add(model);
                } else {
                    log.warn("Requested embedding model {} is not available. Skipping.", modelId);
                }
            }

            if (availableModels.isEmpty()) {
                setErrorResponseAndComplete(responseBuilder, 
                        "No available embedding models found. Requested models: " + 
                                embedderOptions.embeddingModels().stream()
                                        .map(EmbeddingModel::name)
                                        .collect(Collectors.joining(", ")), 
                        null, responseObserver);
                return;
            }

            // Process chunks if available and enabled
            boolean chunksProcessed = false;
            if (embedderOptions.checkChunks() && inputDoc.getSemanticResultsCount() > 0) {
                chunksProcessed = processChunks(inputDoc, outputDocBuilder, embedderOptions, availableModels, pipeStepName);
            }

            // Process document fields if chunks were not processed and document field processing is enabled
            if (!chunksProcessed && embedderOptions.checkDocumentFields()) {
                processDocumentFields(inputDoc, outputDocBuilder, embedderOptions, availableModels, pipeStepName);
            }

            // Process custom field mappings
            if (embedderOptions.customFieldMappings() != null && !embedderOptions.customFieldMappings().isEmpty()) {
                processCustomFieldMappings(inputDoc, outputDocBuilder, embedderOptions, availableModels);
            }

            // Process keywords if enabled
            if (embedderOptions.processKeywords() && inputDoc.getKeywordsCount() > 0) {
                processKeywords(inputDoc, outputDocBuilder, embedderOptions, availableModels, pipeStepName);
            }

            // Build the final output document
            PipeDoc outputDoc = outputDocBuilder.build();

            responseBuilder.setSuccess(true);
            responseBuilder.setOutputDoc(outputDoc);
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (Exception e) {
            String errorMessage = String.format("Error in EmbedderService for step %s, stream %s: %s", pipeStepName, streamId, e.getMessage());
            log.error(errorMessage, e);
            setErrorResponseAndComplete(responseBuilder, errorMessage, e, responseObserver);
        }
    }

    /**
     * Processes chunks in the document and adds embeddings to them.
     * 
     * @param inputDoc the input document
     * @param outputDocBuilder the output document builder
     * @param options the embedder options
     * @param availableModels the available embedding models
     * @param pipeStepName the pipe step name
     * @return true if chunks were processed, false otherwise
     */
    private boolean processChunks(PipeDoc inputDoc, PipeDoc.Builder outputDocBuilder, 
                                 EmbedderOptions options, List<EmbeddingModel> availableModels,
                                 String pipeStepName) {
        boolean chunksProcessed = false;

        // DO NOT clear existing semantic results - we want to ADD new ones with embeddings
        // This allows multiple embedders to run in sequence, each adding their own embeddings

        // Process each semantic result
        for (int i = 0; i < inputDoc.getSemanticResultsCount(); i++) {
            SemanticProcessingResult result = inputDoc.getSemanticResults(i);

            // Skip if no chunks
            if (result.getChunksCount() == 0) {
                continue;
            }

            // Process each model
            for (EmbeddingModel model : availableModels) {
                String modelId = model.name();
                Vectorizer vectorizer = vectorizers.get(modelId);

                // Create a new semantic result with the same properties but with embeddings
                SemanticProcessingResult.Builder newResultBuilder = result.toBuilder()
                        .setEmbeddingConfigId(modelId);

                // Format the result set name
                String resultSetName = String.format(
                        options.resultSetNameTemplate(),
                        pipeStepName,
                        modelId
                ).replaceAll("[^a-zA-Z0-9_\\-]", "_");
                newResultBuilder.setResultSetName(resultSetName);

                // Process each chunk
                List<String> chunkTexts = new ArrayList<>();
                for (int j = 0; j < result.getChunksCount(); j++) {
                    SemanticChunk chunk = result.getChunks(j);
                    chunkTexts.add(chunk.getEmbeddingInfo().getTextContent());
                }

                // Generate embeddings in batch
                List<float[]> embeddings = vectorizer.batchEmbeddings(chunkTexts);

                // Update chunks with embeddings
                for (int j = 0; j < result.getChunksCount(); j++) {
                    SemanticChunk chunk = result.getChunks(j);
                    float[] embedding = embeddings.get(j);

                    ChunkEmbedding.Builder embeddingInfoBuilder = chunk.getEmbeddingInfo().toBuilder();
                    for (float value : embedding) {
                        embeddingInfoBuilder.addVector(value);
                    }

                    SemanticChunk.Builder chunkBuilder = chunk.toBuilder()
                            .setEmbeddingInfo(embeddingInfoBuilder);

                    newResultBuilder.setChunks(j, chunkBuilder.build());
                }

                // Add the new result to the output document
                SemanticProcessingResult newResult = newResultBuilder.build();
                outputDocBuilder.addSemanticResults(newResult);
                chunksProcessed = true;

                log.info("{}Added embeddings to {} chunks using model {} for document ID: {}", 
                        options.logPrefix(), result.getChunksCount(), modelId, inputDoc.getId());
            }
        }

        return chunksProcessed;
    }

    /**
     * Processes document fields and adds embeddings to them.
     * 
     * @param inputDoc the input document
     * @param outputDocBuilder the output document builder
     * @param options the embedder options
     * @param availableModels the available embedding models
     * @param pipeStepName the pipe step name
     */
    private void processDocumentFields(PipeDoc inputDoc, PipeDoc.Builder outputDocBuilder, 
                                      EmbedderOptions options, List<EmbeddingModel> availableModels,
                                      String pipeStepName) {
        // Process each field
        for (String fieldName : options.documentFields()) {
            String fieldText = null;

            // Get the field text
            if ("body".equals(fieldName) && inputDoc.hasBody()) {
                fieldText = inputDoc.getBody();
            } else if ("title".equals(fieldName) && inputDoc.hasTitle()) {
                fieldText = inputDoc.getTitle();
            }

            // Skip if field is empty
            if (StringUtils.isBlank(fieldText)) {
                continue;
            }

            // Process each model
            for (EmbeddingModel model : availableModels) {
                String modelId = model.name();
                Vectorizer vectorizer = vectorizers.get(modelId);

                // Generate embedding
                float[] embedding = vectorizer.embeddings(fieldText);

                // Create embedding object
                Embedding.Builder embeddingBuilder = Embedding.newBuilder()
                        .setModelId(modelId);
                for (float value : embedding) {
                    embeddingBuilder.addVector(value);
                }

                // Add embedding to the document
                String embeddingName = fieldName + "_" + modelId.toLowerCase();
                outputDocBuilder.putNamedEmbeddings(embeddingName, embeddingBuilder.build());

                log.info("{}Added embedding for field {} using model {} for document ID: {}", 
                        options.logPrefix(), fieldName, modelId, inputDoc.getId());
            }
        }
    }

    /**
     * Processes custom field mappings and adds embeddings to the document.
     * 
     * @param inputDoc the input document
     * @param outputDocBuilder the output document builder
     * @param options the embedder options
     * @param availableModels the available embedding models
     */
    private void processCustomFieldMappings(PipeDoc inputDoc, PipeDoc.Builder outputDocBuilder, 
                                           EmbedderOptions options, List<EmbeddingModel> availableModels) {
        // Process each mapping
        for (EmbedderOptions.FieldMapping mapping : options.customFieldMappings()) {
            String sourceField = mapping.sourceField();
            String targetField = mapping.targetField();

            // Get the source field value from custom_data
            if (inputDoc.hasCustomData() && inputDoc.getCustomData().containsFields(sourceField)) {
                Value value = inputDoc.getCustomData().getFieldsOrThrow(sourceField);
                String fieldText = null;

                // Extract text based on value type
                if (value.hasStringValue()) {
                    fieldText = value.getStringValue();
                } else if (value.hasStructValue()) {
                    // Convert struct to JSON string
                    try {
                        fieldText = JsonFormat.printer().print(value.getStructValue());
                    } catch (Exception e) {
                        log.warn("{}Failed to convert struct to string for field {}: {}", 
                                options.logPrefix(), sourceField, e.getMessage());
                    }
                } else if (value.hasListValue()) {
                    // Join list values with space
                    fieldText = value.getListValue().getValuesList().stream()
                            .map(v -> v.hasStringValue() ? v.getStringValue() : v.toString())
                            .collect(Collectors.joining(" "));
                }

                // Skip if field is empty
                if (StringUtils.isBlank(fieldText)) {
                    continue;
                }

                // Process each model
                for (EmbeddingModel model : availableModels) {
                    String modelId = model.name();
                    Vectorizer vectorizer = vectorizers.get(modelId);

                    // Generate embedding
                    float[] embedding = vectorizer.embeddings(fieldText);

                    // Create embedding object
                    Embedding.Builder embeddingBuilder = Embedding.newBuilder()
                            .setModelId(modelId);
                    for (float value1 : embedding) {
                        embeddingBuilder.addVector(value1);
                    }

                    // Add embedding to the document
                    String embeddingName = targetField + "_" + modelId.toLowerCase();
                    outputDocBuilder.putNamedEmbeddings(embeddingName, embeddingBuilder.build());

                    log.info("{}Added embedding for custom field mapping {}:{} using model {} for document ID: {}", 
                            options.logPrefix(), sourceField, targetField, modelId, inputDoc.getId());
                }
            }
        }
    }

    /**
     * Processes keywords and adds embeddings to the document.
     * 
     * @param inputDoc the input document
     * @param outputDocBuilder the output document builder
     * @param options the embedder options
     * @param availableModels the available embedding models
     * @param pipeStepName the pipe step name
     */
    private void processKeywords(PipeDoc inputDoc, PipeDoc.Builder outputDocBuilder, 
                                EmbedderOptions options, List<EmbeddingModel> availableModels,
                                String pipeStepName) {
        List<String> keywords = inputDoc.getKeywordsList();

        // Skip if no keywords
        if (keywords.isEmpty()) {
            return;
        }

        // Process each n-gram size
        for (int ngramSize : options.keywordNgramSizes()) {
            // Generate n-grams
            List<String> ngrams = generateNgrams(keywords, ngramSize);

            // Process each model
            for (EmbeddingModel model : availableModels) {
                String modelId = model.name();
                Vectorizer vectorizer = vectorizers.get(modelId);

                // Process each n-gram
                for (String ngram : ngrams) {
                    // Generate embedding
                    float[] embedding = vectorizer.embeddings(ngram);

                    // Create embedding object
                    Embedding.Builder embeddingBuilder = Embedding.newBuilder()
                            .setModelId(modelId);
                    for (float value : embedding) {
                        embeddingBuilder.addVector(value);
                    }

                    // Add embedding to the document
                    String embeddingName = "keywords_" + ngramSize + "_" + modelId.toLowerCase();
                    if (ngrams.size() > 1) {
                        // If multiple n-grams, add index to name
                        embeddingName += "_" + ngrams.indexOf(ngram);
                    }
                    outputDocBuilder.putNamedEmbeddings(embeddingName, embeddingBuilder.build());
                }

                log.info("{}Added {} keyword {}-gram embeddings using model {} for document ID: {}", 
                        options.logPrefix(), ngrams.size(), ngramSize, modelId, inputDoc.getId());
            }
        }
    }

    /**
     * Generates n-grams from a list of keywords.
     * 
     * @param keywords the list of keywords
     * @param n the n-gram size
     * @return the list of n-grams
     */
    private List<String> generateNgrams(List<String> keywords, int n) {
        if (n <= 0 || keywords.isEmpty()) {
            return Collections.emptyList();
        }

        // If n is 1 or greater than the number of keywords, return all keywords as a single string
        if (n == 1 || n >= keywords.size()) {
            return List.of(String.join(" ", keywords));
        }

        // Generate n-grams
        List<String> ngrams = new ArrayList<>();
        for (int i = 0; i <= keywords.size() - n; i++) {
            String ngram = String.join(" ", keywords.subList(i, i + n));
            ngrams.add(ngram);
        }

        return ngrams;
    }

    /**
     * Sets an error response and completes the response observer.
     * 
     * @param responseBuilder the response builder
     * @param errorMessage the error message
     * @param e the exception, or null if none
     * @param responseObserver the response observer
     */
    private void setErrorResponseAndComplete(
            ProcessResponse.Builder responseBuilder,
            String errorMessage,
            Exception e,
            StreamObserver<ProcessResponse> responseObserver) {

        responseBuilder.setSuccess(false);
        responseBuilder.addProcessorLogs(errorMessage);

        Struct.Builder errorDetailsBuilder = Struct.newBuilder();
        errorDetailsBuilder.putFields("error_message", Value.newBuilder().setStringValue(errorMessage).build());
        if (e != null) {
            errorDetailsBuilder.putFields("error_type", Value.newBuilder().setStringValue(e.getClass().getName()).build());
            if (e.getCause() != null) {
                errorDetailsBuilder.putFields("error_cause", Value.newBuilder().setStringValue(e.getCause().getMessage()).build());
            }
        }
        responseBuilder.setErrorDetails(errorDetailsBuilder.build());
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
