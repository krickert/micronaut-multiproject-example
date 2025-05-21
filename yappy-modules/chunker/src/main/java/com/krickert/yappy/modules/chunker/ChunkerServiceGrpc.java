package com.krickert.yappy.modules.chunker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.model.ChunkEmbedding;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.SemanticChunk;
import com.krickert.search.model.SemanticProcessingResult;
import com.krickert.search.model.mapper.MappingException;
import com.krickert.search.sdk.*;
import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Singleton
@GrpcService
@Requires(property = "grpc.services.chunker.enabled", value = "true", defaultValue = "true")
public class ChunkerServiceGrpc extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger log = LoggerFactory.getLogger(ChunkerServiceGrpc.class);
    private final ObjectMapper objectMapper;
    private final OverlapChunker overlapChunker;
    private final ChunkMetadataExtractor metadataExtractor;

    @Inject
    public ChunkerServiceGrpc(
            ObjectMapper objectMapper,
            OverlapChunker overlapChunker,
            ChunkMetadataExtractor metadataExtractor) {
        this.objectMapper = objectMapper;
        this.overlapChunker = overlapChunker;
        this.metadataExtractor = metadataExtractor;
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

            Struct customJsonConfig = config.getCustomJsonConfig();
            ChunkerOptions chunkerOptions;
            if (customJsonConfig != null && customJsonConfig.getFieldsCount() > 0) {
                chunkerOptions = objectMapper.readValue(
                        JsonFormat.printer().print(customJsonConfig),
                        ChunkerOptions.class
                );
            } else {
                log.warn("No custom JSON config provided for ChunkerService. Using defaults. streamId: {}, pipeStepName: {}", streamId, pipeStepName);
                chunkerOptions = new ChunkerOptions();
            }

            if (chunkerOptions.sourceField() == null || chunkerOptions.sourceField().isEmpty()) {
                setErrorResponseAndComplete(responseBuilder, "Missing 'source_field' in ChunkerOptions", null, responseObserver);
                return;
            }

            ChunkingResult chunkingResult = overlapChunker.createChunks(inputDoc, chunkerOptions, streamId, pipeStepName);
            List<Chunk> chunkRecords = chunkingResult.chunks();
            Map<String, String> placeholderToUrlMap = chunkingResult.placeholderToUrlMap();

            if (!chunkRecords.isEmpty()) {
                SemanticProcessingResult.Builder newSemanticResultBuilder = SemanticProcessingResult.newBuilder()
                        .setResultId(UUID.randomUUID().toString())
                        .setSourceFieldName(chunkerOptions.sourceField())
                        .setChunkConfigId(chunkerOptions.chunkConfigId());
                // REMOVED: .setEmbeddingConfigId("none");
                // If not set, it defaults to an empty string, which is appropriate here.

                String resultSetName = String.format(
                        chunkerOptions.resultSetNameTemplate(),
                        pipeStepName,
                        chunkerOptions.chunkConfigId()
                ).replaceAll("[^a-zA-Z0-9_\\-]", "_");
                newSemanticResultBuilder.setResultSetName(resultSetName);

                int currentChunkNumber = 0;
                for (Chunk chunkRecord : chunkRecords) {
                    ChunkEmbedding.Builder chunkEmbeddingBuilder = ChunkEmbedding.newBuilder()
                            .setTextContent(chunkRecord.text())
                            .setChunkId(chunkRecord.id())
                            .setOriginalCharStartOffset(chunkRecord.originalIndexStart())
                            .setOriginalCharEndOffset(chunkRecord.originalIndexEnd())
                            .setChunkConfigId(chunkerOptions.chunkConfigId());

                    boolean containsUrlPlaceholder = (chunkerOptions.preserveUrls() != null && chunkerOptions.preserveUrls()) &&
                            !placeholderToUrlMap.isEmpty() && // Check if map is not empty
                            placeholderToUrlMap.keySet().stream().anyMatch(ph -> chunkRecord.text().contains(ph));

                    Map<String, Value> extractedMetadata = metadataExtractor.extractAllMetadata(
                            chunkRecord.text(),
                            currentChunkNumber,
                            chunkRecords.size(),
                            containsUrlPlaceholder
                    );

                    SemanticChunk.Builder semanticChunkBuilder = SemanticChunk.newBuilder()
                            .setChunkId(chunkRecord.id())
                            .setChunkNumber(currentChunkNumber)
                            .setEmbeddingInfo(chunkEmbeddingBuilder.build())
                            .putAllMetadata(extractedMetadata);

                    newSemanticResultBuilder.addChunks(semanticChunkBuilder.build());
                    currentChunkNumber++;
                }
                outputDocBuilder.addSemanticResults(newSemanticResultBuilder.build());
                responseBuilder.addProcessorLogs(String.format("%sSuccessfully created and added metadata to %d chunks from source field '%s' into result set '%s'.",
                        chunkerOptions.logPrefix(), chunkRecords.size(), chunkerOptions.sourceField(), resultSetName));

            } else {
                responseBuilder.addProcessorLogs(String.format("%sNo content in '%s' to chunk for document ID: %s",
                        chunkerOptions.logPrefix(), chunkerOptions.sourceField(), inputDoc.getId()));
            }

            responseBuilder.setSuccess(true);
            responseBuilder.setOutputDoc(outputDocBuilder.build());
            responseObserver.onNext(responseBuilder.build());
            responseObserver.onCompleted();

        } catch (MappingException e) {
            String errorMessage = String.format("Mapping error in ChunkerService for step %s, stream %s, field '%s': %s", pipeStepName, streamId, e.getFailedRule(), e.getMessage());
            log.error(errorMessage, e);
            setErrorResponseAndComplete(responseBuilder, errorMessage, e, responseObserver);
        } catch (Exception e) {
            String errorMessage = String.format("Error in ChunkerService for step %s, stream %s: %s", pipeStepName, streamId, e.getMessage());
            log.error(errorMessage, e);
            setErrorResponseAndComplete(responseBuilder, errorMessage, e, responseObserver);
        }
    }

    private void setErrorResponseAndComplete(
            ProcessResponse.Builder responseBuilder,
            String errorMessage,
            Exception e,
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