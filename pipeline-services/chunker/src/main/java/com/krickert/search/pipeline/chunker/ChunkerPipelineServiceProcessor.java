package com.krickert.search.pipeline.chunker;

import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import com.krickert.search.chunker.OverlapChunker;
import com.krickert.search.model.*;
import com.krickert.search.model.mapper.MappingException;
import com.krickert.search.model.mapper.PathResolver;
import com.krickert.search.model.mapper.ValueHandler;
import com.krickert.search.pipeline.service.PipeServiceDto;
import com.krickert.search.pipeline.service.PipelineServiceProcessor;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chunker implementation of the PipelineServiceProcessor interface.
 * This implementation chunks text from the PipeDoc based on configuration
 * and stores the chunks in the SemanticDoc structure.
 */
@Singleton
public class ChunkerPipelineServiceProcessor implements PipelineServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChunkerPipelineServiceProcessor.class);
    private static final String DEFAULT_CHUNK_SIZE = "500";
    private static final String DEFAULT_OVERLAP_SIZE = "50";
    private static final String CHUNK_SIZE_CONFIG_KEY = "chunkSize";
    private static final String OVERLAP_SIZE_CONFIG_KEY = "overlapSize";
    private static final String CHUNK_FIELD_CONFIG_KEY = "chunk-field";

    private final OverlapChunker chunker;
    private final PathResolver pathResolver;
    private final ValueHandler valueHandler;

    public ChunkerPipelineServiceProcessor(OverlapChunker chunker) {
        this.chunker = chunker;
        this.pathResolver = new PathResolver();
        this.valueHandler = new ValueHandler(this.pathResolver);
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
            int chunkSize = getChunkSizeFromRequest(pipeStream);
            int overlapSize = getOverlapSizeFromRequest(pipeStream);

            // Get text to chunk based on chunk-field
            String textToChunk = getTextToChunkFromRequest(originalDoc, pipeStream);

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

    // Store the field name being chunked for use in createSemanticDoc
    private String chunkFieldName = "body";

    /**
     * Get the text to chunk based on the chunk-field configuration from PipeRequest.
     * If no chunk-field is defined, it will use the PipeDoc body by default.
     *
     * @param doc The PipeDoc to extract text from
     * @param pipeStream The PipeStream containing the request with configuration
     * @return The text to chunk
     */
    private String getTextToChunkFromRequest(PipeDoc doc, PipeStream pipeStream) {
        try {
            // Reset the chunk field name to default
            chunkFieldName = "body";

            // Get the chunk-field from PipeRequest config
            String chunkField = null;

            // Check PipeRequest config
            if (pipeStream != null && pipeStream.getRequest() != null && 
                pipeStream.getRequest().getConfigMap().containsKey(CHUNK_FIELD_CONFIG_KEY)) {
                chunkField = pipeStream.getRequest().getConfigMap().get(CHUNK_FIELD_CONFIG_KEY);
                log.debug("Using chunk-field from PipeRequest config: {}", chunkField);
                log.debug("[DEBUG_LOG] Using chunk-field from PipeRequest config: " + chunkField);

                // Print all config entries for debugging
                log.debug("[DEBUG_LOG] PipeRequest config entries:");
                for (Map.Entry<String, String> entry : pipeStream.getRequest().getConfigMap().entrySet()) {
                    log.debug("[DEBUG_LOG]   " + entry.getKey() + " = " + entry.getValue());
                }
            } else {
                log.debug("[DEBUG_LOG] No chunk-field found in PipeRequest config");
                if (pipeStream != null && pipeStream.getRequest() != null) {
                    log.debug("[DEBUG_LOG] PipeRequest config entries:");
                    for (Map.Entry<String, String> entry : pipeStream.getRequest().getConfigMap().entrySet()) {
                        log.debug("[DEBUG_LOG]   " + entry.getKey() + " = " + entry.getValue());
                    }
                }
            }

            // If no chunk-field specified, default to "body"
            if (chunkField == null || chunkField.isEmpty()) {
                log.debug("No chunk-field specified, using 'body' field");
                return doc.getBody();
            }

            // Set the chunk field name for use in createSemanticDoc
            chunkFieldName = chunkField;

            // Get the field value based on the chunk-field
            log.debug("Looking for chunk-field: {}", chunkField);
            Object fieldValue = getFieldValueByPath(doc, chunkField);

            if (fieldValue == null) {
                log.debug("Field '{}' not found, defaulting to 'body' field", chunkField);
                chunkFieldName = "body";
                return doc.getBody();
            }

            log.debug("Found field '{}' with value type: {}", chunkField, fieldValue.getClass().getName());

            // Handle different data types
            if (fieldValue instanceof String) {
                return (String) fieldValue;
            } else if (fieldValue instanceof List) {
                // For arrays, concatenate with spaces
                List<?> list = (List<?>) fieldValue;
                StringBuilder sb = new StringBuilder();
                for (Object item : list) {
                    if (sb.length() > 0) {
                        sb.append(" ");
                    }
                    sb.append(item.toString());
                }
                return sb.toString();
            } else if (fieldValue instanceof Map) {
                // For maps, convert to JSON string and log a warning
                log.warn("Field '{}' is a map, converting to JSON string for chunking", chunkField);
                return fieldValue.toString();
            } else {
                // For other types, convert to string
                return fieldValue.toString();
            }
        } catch (Exception e) {
            log.error("Error getting text to chunk: {}", e.getMessage(), e);
            // Fall back to using the body if there's any error
            chunkFieldName = "body";
            return doc.getBody();
        }
    }

    /**
     * Get the value of a field from a PipeDoc.
     * Checks standard fields first, then looks in custom_data.
     *
     * @param doc The PipeDoc to extract the field from
     * @param fieldName The name of the field to extract
     * @return The field value as a String, or null if not found
     */
    private String getFieldValue(PipeDoc doc, String fieldName) {
        Object value = getFieldValueByPath(doc, fieldName);
        return value != null ? value.toString() : null;
    }

    /**
     * Get the value of a field from a PipeDoc by path.
     * Uses the ProtoMapper's PathResolver and ValueHandler to resolve paths and extract values.
     * Handles standard fields, nested messages, and custom_data fields with dot notation.
     *
     * @param doc The PipeDoc to extract the field from
     * @param fieldPath The path to the field to extract (e.g., "title", "custom_data.title", "description")
     * @return The field value as an Object, or null if not found
     */
    private Object getFieldValueByPath(PipeDoc doc, String fieldPath) {
        if (fieldPath == null || fieldPath.isEmpty()) {
            log.debug("[DEBUG_LOG] Field path is null or empty");
            return null;
        }

        log.debug("[DEBUG_LOG] Looking for field path: " + fieldPath);

        // First try the path as is
        try {
            Object value = valueHandler.getValue(doc, fieldPath, "getFieldValueByPath");
            if (value != null) {
                log.debug("[DEBUG_LOG] Found value of type: {}", value.getClass().getName());
                return value;
            }
        } catch (MappingException e) {
            log.debug("[DEBUG_LOG] Error resolving path '{}': {}", fieldPath, e.getMessage());
            // Continue to try other approaches
        }

        // If the path doesn't explicitly reference custom_data but the field might be in custom_data,
        // try with custom_data prefix
        if (!fieldPath.startsWith("custom_data.") && doc.hasCustomData()) {
            String customDataPath = "custom_data." + fieldPath;
            log.debug("[DEBUG_LOG] Trying with custom_data prefix: {}", customDataPath);

            try {
                Object value = valueHandler.getValue(doc, customDataPath, "getFieldValueByPath");
                if (value != null) {
                    log.debug("[DEBUG_LOG] Found value in custom_data of type: {}", value.getClass().getName());
                    return value;
                }
            } catch (MappingException e) {
                log.debug("[DEBUG_LOG] Error resolving custom_data path '{}': {}", customDataPath, e.getMessage());
            }
        }

        // Check standard fields directly as a fallback
        log.debug("[DEBUG_LOG] Checking standard fields directly for: {}", fieldPath);
        switch (fieldPath) {
            case "body":
                log.debug("[DEBUG_LOG] Found standard field: body");
                return doc.getBody();
            case "title":
                log.debug("[DEBUG_LOG] Found standard field: title");
                return doc.getTitle();
            case "id":
                log.debug("[DEBUG_LOG] Found standard field: id");
                return doc.getId();
            case "document_type":
                log.debug("[DEBUG_LOG] Found standard field: document_type");
                return doc.getDocumentType();
            case "revision_id":
                log.debug("[DEBUG_LOG] Found standard field: revision_id");
                return doc.getRevisionId();
            case "keywords":
                log.debug("[DEBUG_LOG] Found standard field: keywords");
                return doc.getKeywordsList();
            default:
                // If not found anywhere, return null
                log.debug("[DEBUG_LOG] Field '{}' not found in PipeDoc standard fields or custom_data", fieldPath);
                return null;
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
        // Include the field name in the chunk config ID
        String configId = String.format("%s-%d-%d", chunkFieldName, chunkSize, overlapSize);

        SemanticDoc.Builder semanticDocBuilder = SemanticDoc.newBuilder()
                .setParentId(originalDoc.getId())
                .setParentField(chunkFieldName) // Use the chunk field name
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
     * Get the chunk size from PipeRequest configuration.
     * Defaults to 500 if not specified.
     *
     * @param pipeStream The PipeStream containing the request with configuration
     * @return The chunk size as an integer
     */
    private int getChunkSizeFromRequest(PipeStream pipeStream) {
        try {
            if (pipeStream != null && pipeStream.getRequest() != null) {
                Map<String, String> configMap = pipeStream.getRequest().getConfigMap();
                if (configMap.containsKey(CHUNK_SIZE_CONFIG_KEY)) {
                    String chunkSizeStr = configMap.get(CHUNK_SIZE_CONFIG_KEY);
                    return Integer.parseInt(chunkSizeStr);
                }
            }

            // Default if not found in request
            log.debug("Using default chunk size: {}", DEFAULT_CHUNK_SIZE);
            return Integer.parseInt(DEFAULT_CHUNK_SIZE);
        } catch (NumberFormatException e) {
            log.warn("Invalid chunk size, using default: {}", DEFAULT_CHUNK_SIZE);
            return Integer.parseInt(DEFAULT_CHUNK_SIZE);
        } catch (Exception e) {
            log.error("Error getting chunk size: {}", e.getMessage(), e);
            return Integer.parseInt(DEFAULT_CHUNK_SIZE);
        }
    }

    /**
     * Get the overlap size from PipeRequest configuration.
     * Defaults to 50 if not specified.
     *
     * @param pipeStream The PipeStream containing the request with configuration
     * @return The overlap size as an integer
     */
    private int getOverlapSizeFromRequest(PipeStream pipeStream) {
        try {
            if (pipeStream != null && pipeStream.getRequest() != null) {
                Map<String, String> configMap = pipeStream.getRequest().getConfigMap();
                if (configMap.containsKey(OVERLAP_SIZE_CONFIG_KEY)) {
                    String overlapSizeStr = configMap.get(OVERLAP_SIZE_CONFIG_KEY);
                    return Integer.parseInt(overlapSizeStr);
                }
            }

            // Default if not found in request
            log.debug("Using default overlap size: {}", DEFAULT_OVERLAP_SIZE);
            return Integer.parseInt(DEFAULT_OVERLAP_SIZE);
        } catch (NumberFormatException e) {
            log.warn("Invalid overlap size, using default: {}", DEFAULT_OVERLAP_SIZE);
            return Integer.parseInt(DEFAULT_OVERLAP_SIZE);
        } catch (Exception e) {
            log.error("Error getting overlap size: {}", e.getMessage(), e);
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
