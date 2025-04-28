package com.krickert.search.pipeline.chunker;

import com.google.protobuf.Timestamp;
import com.krickert.search.chunker.OverlapChunker;
import com.krickert.search.model.*;
import com.krickert.search.model.mapper.MappingException;
import com.krickert.search.model.mapper.ProtoMapper;
import com.krickert.search.pipeline.config.ServiceConfiguration;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Chunker implementation of the PipelineServiceProcessor interface.
 * This implementation chunks text from the PipeDoc based on configured mappings
 * and stores the chunks in the SemanticDoc structure.
 */
@Singleton
public class ChunkerPipelineServiceProcessor implements PipelineServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkerPipelineServiceProcessor.class);
    private static final String DEFAULT_CHUNK_SIZE = "500";
    private static final String DEFAULT_OVERLAP_SIZE = "50";
    private static final String CHUNK_SIZE_CONFIG_KEY = "chunkSize";
    private static final String OVERLAP_SIZE_CONFIG_KEY = "overlapSize";
    private static final String MAPPINGS_CONFIG_KEY = "mappings";

    private final OverlapChunker chunker;
    private final ProtoMapper protoMapper;
    private final ServiceConfiguration serviceConfiguration;

    public ChunkerPipelineServiceProcessor(OverlapChunker chunker, ProtoMapper protoMapper, ServiceConfiguration serviceConfiguration) {
        this.chunker = chunker;
        this.protoMapper = protoMapper;
        this.serviceConfiguration = serviceConfiguration;
    }

    @Override
    public PipeServiceDto process(PipeStream pipeStream) {
        log.debug("Processing PipeStream with ChunkerPipelineServiceProcessor");
        PipeServiceDto serviceDto = new PipeServiceDto();

        try {
            // Get the PipeDoc from the request
            PipeDoc originalDoc = pipeStream.getRequest().getDoc();

            // Create a builder from the original doc
            PipeDoc.Builder docBuilder = originalDoc.toBuilder();

            // Update the last_modified timestamp to current time
            Timestamp currentTime = getCurrentTimestamp();
            docBuilder.setLastModified(currentTime);

            // Get chunk size and overlap size from configuration
            int chunkSize = getChunkSize();
            int overlapSize = getOverlapSize();
            
            // Get text to chunk based on mappings
            String textToChunk = getTextToChunk(originalDoc);
            
            // Chunk the text
            List<String> chunks = chunker.chunkTextSplitNewline(textToChunk, chunkSize, overlapSize);
            
            // Create SemanticDoc with chunks
            SemanticDoc semanticDoc = createSemanticDoc(originalDoc, chunks, chunkSize, overlapSize);
            
            // Set the SemanticDoc in the PipeDoc
            docBuilder.setChunkEmbeddings(semanticDoc);

            // Build the updated PipeDoc
            PipeDoc updatedDoc = docBuilder.build();

            log.info("Successfully processed document with ID: {}, created {} chunks", 
                    updatedDoc.getId(), chunks.size());

            // Create a success response
            PipeResponse response = PipeResponse.newBuilder()
                    .setSuccess(true)
                    .build();

            // Set both the response and updated doc in the DTO
            serviceDto.setResponse(response);
            serviceDto.setPipeDoc(updatedDoc);

            return serviceDto;
        } catch (Exception e) {
            log.error("Error processing PipeStream: {}", e.getMessage(), e);

            // Return an error response
            ErrorData errorData = ErrorData.newBuilder()
                    .setErrorMessage("Error processing PipeStream: " + e.getMessage())
                    .build();

            PipeResponse response = PipeResponse.newBuilder()
                    .setSuccess(false)
                    .setErrorDate(errorData)
                    .build();

            serviceDto.setResponse(response);
            return serviceDto;
        }
    }

    /**
     * Get the text to chunk based on mappings configuration.
     * If no mapping is defined, it will use the PipeDoc body by default.
     *
     * @param doc The PipeDoc to extract text from
     * @return The text to chunk
     */
    private String getTextToChunk(PipeDoc doc) {
        String mappingsConfig = serviceConfiguration.getConfigParams().getOrDefault(MAPPINGS_CONFIG_KEY, "");
        
        if (mappingsConfig.isEmpty()) {
            // Default to using the body if no mappings are defined
            log.debug("No mappings defined, using PipeDoc body");
            return doc.getBody();
        }
        
        try {
            // Parse the mappings configuration
            List<String> mappingRules = Arrays.asList(mappingsConfig.split(","));
            
            // Use ProtoMapper to extract the text based on the mapping rules
            // We'll create a temporary PipeDoc with just the extracted text in the body field
            PipeDoc.Builder tempDocBuilder = PipeDoc.newBuilder();
            protoMapper.mapOnto(doc, tempDocBuilder, mappingRules);
            
            // If the mapping didn't result in a body, fall back to the original body
            if (tempDocBuilder.getBody().isEmpty()) {
                log.warn("Mapping did not result in text, falling back to PipeDoc body");
                return doc.getBody();
            }
            
            return tempDocBuilder.getBody();
        } catch (MappingException e) {
            log.error("Error applying mapping rules: {}", e.getMessage(), e);
            // Fall back to using the body if there's an error with the mapping
            return doc.getBody();
        }
    }

    /**
     * Create a SemanticDoc with the chunked text.
     *
     * @param originalDoc The original PipeDoc
     * @param chunks The list of text chunks
     * @param chunkSize The chunk size used
     * @param overlapSize The overlap size used
     * @return A SemanticDoc containing the chunks
     */
    private SemanticDoc createSemanticDoc(PipeDoc originalDoc, List<String> chunks, int chunkSize, int overlapSize) {
        String configId = String.format("chunk_%d_overlap_%d", chunkSize, overlapSize);
        
        SemanticDoc.Builder semanticDocBuilder = SemanticDoc.newBuilder()
                .setParentId(originalDoc.getId())
                .setParentField("body") // Default to body, can be updated based on mapping
                .setChunkConfigId(configId);
        
        List<SemanticChunk> semanticChunks = new ArrayList<>();
        
        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            
            // Create a ChunkEmbedding with the text (embedding values will be added later)
            ChunkEmbedding chunkEmbedding = ChunkEmbedding.newBuilder()
                    .setEmbeddingText(chunk)
                    .build();
            
            // Create a SemanticChunk with a unique ID
            SemanticChunk semanticChunk = SemanticChunk.newBuilder()
                    .setChunkId(generateChunkId(originalDoc.getId(), i, chunkSize, overlapSize))
                    .setChunkNumber(i)
                    .setEmbedding(chunkEmbedding)
                    .build();
            
            semanticChunks.add(semanticChunk);
        }
        
        semanticDocBuilder.addAllChunks(semanticChunks);
        
        return semanticDocBuilder.build();
    }
    
    /**
     * Generate a unique ID for a chunk.
     *
     * @param docId The ID of the parent document
     * @param chunkNumber The number of the chunk
     * @param chunkSize The chunk size used
     * @param overlapSize The overlap size used
     * @return A unique ID for the chunk
     */
    private String generateChunkId(String docId, int chunkNumber, int chunkSize, int overlapSize) {
        return String.format("%s_chunk_%d_size_%d_overlap_%d_%s", 
                docId, chunkNumber, chunkSize, overlapSize, UUID.randomUUID().toString().substring(0, 8));
    }
    
    /**
     * Get the chunk size from configuration.
     * Defaults to 500 if not specified.
     *
     * @return The chunk size as an integer
     */
    private int getChunkSize() {
        String chunkSizeStr = serviceConfiguration.getConfigParams().getOrDefault(CHUNK_SIZE_CONFIG_KEY, DEFAULT_CHUNK_SIZE);
        try {
            return Integer.parseInt(chunkSizeStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid chunk size: {}, using default: {}", chunkSizeStr, DEFAULT_CHUNK_SIZE);
            return Integer.parseInt(DEFAULT_CHUNK_SIZE);
        }
    }
    
    /**
     * Get the overlap size from configuration.
     * Defaults to 50 if not specified.
     *
     * @return The overlap size as an integer
     */
    private int getOverlapSize() {
        String overlapSizeStr = serviceConfiguration.getConfigParams().getOrDefault(OVERLAP_SIZE_CONFIG_KEY, DEFAULT_OVERLAP_SIZE);
        try {
            return Integer.parseInt(overlapSizeStr);
        } catch (NumberFormatException e) {
            log.warn("Invalid overlap size: {}, using default: {}", overlapSizeStr, DEFAULT_OVERLAP_SIZE);
            return Integer.parseInt(DEFAULT_OVERLAP_SIZE);
        }
    }
    
    /**
     * Get the current timestamp as a Protobuf Timestamp
     * 
     * @return Current time as Protobuf Timestamp
     */
    private Timestamp getCurrentTimestamp() {
        Instant now = Instant.now();
        return Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano())
                .build();
    }
}