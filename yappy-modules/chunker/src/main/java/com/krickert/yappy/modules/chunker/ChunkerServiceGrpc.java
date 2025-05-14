package com.krickert.yappy.modules.chunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value; // Make sure this is com.google.protobuf.Value
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.model.ChunkEmbedding;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticChunk; // Added import
import com.krickert.search.model.SemanticProcessingResult; // Added import
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import io.grpc.stub.StreamObserver;
// import io.micronaut.context.annotation.Context; // Not typically needed for GrpcService
// import io.micronaut.core.annotation.ReflectiveAccess; // Needed for ChunkerOptions if reflection used
import io.micronaut.scheduling.TaskExecutors; // Keep if you use it, but gRPC handles its own threads
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.krickert.search.model.mapper.MappingException;


import java.util.List;
import java.util.Map; // For metadata
import java.util.UUID;
// import java.util.concurrent.ExecutorService; // Keep if used for other async tasks

@Singleton
public class ChunkerServiceGrpc extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger log = LoggerFactory.getLogger(ChunkerServiceGrpc.class);
    private final ObjectMapper objectMapper;
    private final OverlapChunker overlapChunker;
    private final ChunkMetadataExtractor metadataExtractor; // Inject the new service
    // private final ExecutorService executorService; // Keep if used, gRPC has its own threading

    @Inject
    public ChunkerServiceGrpc( // Removed @Named(TaskExecutors.IO) ExecutorService executorService for now
                               ObjectMapper objectMapper,
                               OverlapChunker overlapChunker,
                               ChunkMetadataExtractor metadataExtractor) { // Add to constructor
        // this.executorService = executorService;
        this.objectMapper = objectMapper;
        this.overlapChunker = overlapChunker;
        this.metadataExtractor = metadataExtractor; // Initialize
    }


    @Override
    public void processData(ProcessRequest request, StreamObserver<ProcessResponse> responseObserver) {
        PipeDoc inputDoc = request.getDocument();
        ProcessConfiguration config = request.getConfig();
        ServiceMetadata metadata = request.getMetadata();
        String streamId = metadata.getStreamId();
        String pipeStepName = metadata.getPipeStepName();

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();
        PipeDoc.Builder outputDocBuilder = inputDoc.toBuilder(); // Start with a copy of input

        try {
            log.info("Processing document ID: {} for step: {} in stream: {}", inputDoc.getId(), pipeStepName, streamId);

            Struct customJsonConfig = config.getCustomJsonConfig();
            ChunkerOptions chunkerOptions;
            if (customJsonConfig != null && customJsonConfig.getFieldsCount() > 0) {
                chunkerOptions = objectMapper.readValue(
                        JsonFormat.printer().print(customJsonConfig),
                        ChunkerOptions.class
                );
            } else {
                log.warn("No custom JSON config provided for ChunkerService. Using defaults. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
                chunkerOptions = new ChunkerOptions(); // Use default constructor
            }

            if (chunkerOptions.sourceField() == null || chunkerOptions.sourceField().isEmpty()) {
                setErrorResponseAndComplete(responseBuilder, "Missing 'sourceField' in ChunkerOptions", null, responseObserver);
                return;
            }
            // The ChunkerOptions record now has defaults, so this check might be less critical unless an empty string is explicitly passed
            if (chunkerOptions.chunkIdTemplate() == null || chunkerOptions.chunkIdTemplate().isEmpty()){
                log.warn("chunkIdTemplate not specified in ChunkerOptions, ChunkerOptions should provide a default. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
                // ChunkerOptions constructor now handles defaults.
            }


            List<Chunk> chunkRecords = overlapChunker.createChunks(inputDoc, chunkerOptions, streamId, pipeStepName);

            if (!chunkRecords.isEmpty()) {
                SemanticProcessingResult.Builder semanticProcessingResultBuilder = SemanticProcessingResult.newBuilder()
                        .setResultId(UUID.randomUUID().toString()) // Generate a unique ID for this processing result
                        .setSourceFieldName(chunkerOptions.sourceField())
                        .setChunkConfigId(chunkerOptions.chunkConfigId()) // Get from options
                        .setEmbeddingConfigId("none"); // Embedding is not done in this step

                // Generate result_set_name using the template from ChunkerOptions
                // The template might use placeholders like %s for pipeStepName or sourceFieldName
                // For simplicity, let's assume it can use pipeStepName. Adjust if template is more complex.
                String resultSetName = String.format(
                        chunkerOptions.resultSetNameTemplate() != null ? chunkerOptions.resultSetNameTemplate() : "%s_chunks_%s", // Default fallback
                        pipeStepName, // Could also be chunkerOptions.sourceField() or other context
                        chunkerOptions.chunkConfigId()
                ).replaceAll("[^a-zA-Z0-9_\\-]", "_"); // Sanitize
                semanticProcessingResultBuilder.setResultSetName(resultSetName);


                int currentChunkNumber = 0;
                for (Chunk chunkRecord : chunkRecords) {
                    ChunkEmbedding.Builder chunkEmbeddingBuilder = ChunkEmbedding.newBuilder()
                            .setTextContent(chunkRecord.text())
                            .setChunkId(chunkRecord.id()) // This ID comes from OverlapChunker
                            .setOriginalCharStartOffset(chunkRecord.originalIndexStart())
                            .setOriginalCharEndOffset(chunkRecord.originalIndexEnd())
                            .setChunkConfigId(chunkerOptions.chunkConfigId());
                    // Vector not populated here

                    // --- Metadata Extraction ---
                    boolean containsUrlPlaceholder = chunkRecord.text().contains("urlplaceholder"); // Simple check
                    Map<String, Value> extractedMetadata = metadataExtractor.extractAllMetadata(
                            chunkRecord.text(),
                            currentChunkNumber,
                            chunkRecords.size(),
                            containsUrlPlaceholder
                    );

                    SemanticChunk.Builder semanticChunkBuilder = SemanticChunk.newBuilder()
                            .setChunkId(chunkRecord.id()) // Using the ID from OverlapChunker
                            .setChunkNumber(currentChunkNumber)
                            .setEmbeddingInfo(chunkEmbeddingBuilder.build())
                            .putAllMetadata(extractedMetadata);

                    semanticProcessingResultBuilder.addChunks(semanticChunkBuilder.build());
                    currentChunkNumber++;
                }
                outputDocBuilder.addSemanticResults(semanticProcessingResultBuilder.build());
                responseBuilder.addProcessorLogs(String.format("%sSuccessfully created and added metadata to %d chunks from source field '%s' into result set '%s'.",
                        chunkerOptions.logPrefix(), chunkRecords.size(), chunkerOptions.sourceField(), resultSetName));

            } else {
                responseBuilder.addProcessorLogs(String.format("%sNo content in '%s' to chunk for document ID: %s", chunkerOptions.logPrefix(), chunkerOptions.sourceField(), inputDoc.getId()));
            }

            responseBuilder.setSuccess(true);
            responseBuilder.setOutputDoc(outputDocBuilder.build());
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (MappingException e) { // Catch MappingException from OverlapChunker
            String errorMessage = String.format("Mapping error in ChunkerService for step %s, stream %s, field '%s': %s", pipeStepName, streamId, e.getFailedRule(), e.getMessage());
            log.error(errorMessage, e);
            setErrorResponseAndComplete(responseBuilder, errorMessage, e, responseObserver);
        }
        catch (Exception e) {
            String errorMessage = String.format("Error in ChunkerService for step %s, stream %s: %s", pipeStepName, streamId, e.getMessage());
            log.error(errorMessage, e);
            setErrorResponseAndComplete(responseBuilder, errorMessage, e, responseObserver);
        }
    }

    private void setErrorResponseAndComplete(
            ProcessResponse.Builder responseBuilder,
            String errorMessage,
            Exception e, // Can be null
            StreamObserver<ProcessResponse> responseObserver) {

        responseBuilder.setSuccess(false);
        responseBuilder.addProcessorLogs(errorMessage);

        com.google.protobuf.Struct.Builder errorDetailsBuilder = com.google.protobuf.Struct.newBuilder();
        errorDetailsBuilder.putFields("error_message", com.google.protobuf.Value.newBuilder().setStringValue(errorMessage).build());
        if (e != null) {
            errorDetailsBuilder.putFields("error_type", com.google.protobuf.Value.newBuilder().setStringValue(e.getClass().getName()).build());
            if (e.getCause() != null) {
                errorDetailsBuilder.putFields("error_cause", com.google.protobuf.Value.newBuilder().setStringValue(e.getCause().getMessage()).build());
            }
        }
        responseBuilder.setErrorDetails(errorDetailsBuilder.build());
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
}
