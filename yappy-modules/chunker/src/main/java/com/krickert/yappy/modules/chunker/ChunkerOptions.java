package com.krickert.yappy.modules.chunker;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// Add this annotation to ignore unknown properties
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChunkerOptions(
        @JsonProperty("source_field") String sourceField,
        @JsonProperty("chunk_size") int chunkSize,
        @JsonProperty("chunk_overlap") int chunkOverlap,
        @JsonProperty("chunk_id_template") String chunkIdTemplate,
        @JsonProperty("chunk_config_id") String chunkConfigId,
        @JsonProperty("result_set_name_template") String resultSetNameTemplate,
        @JsonProperty("log_prefix") String logPrefix
) {
    public static final String DEFAULT_SOURCE_FIELD = "body";
    public static final int DEFAULT_CHUNK_SIZE = 500;
    public static final int DEFAULT_CHUNK_OVERLAP = 50;
    public static final String DEFAULT_CHUNK_ID_TEMPLATE = "%s_%s_chunk_%d"; // e.g., streamId, documentId, chunkIndex
    public static final String DEFAULT_CHUNK_CONFIG_ID = "default_overlap_500_50";
    public static final String DEFAULT_RESULT_SET_NAME_TEMPLATE = "%s_chunks_default_overlap_500_50"; // e.g., pipeStepName
    public static final String DEFAULT_LOG_PREFIX = ""; // Default to empty string

    public ChunkerOptions() {
        this(DEFAULT_SOURCE_FIELD,
                DEFAULT_CHUNK_SIZE,
                DEFAULT_CHUNK_OVERLAP,
                DEFAULT_CHUNK_ID_TEMPLATE,
                DEFAULT_CHUNK_CONFIG_ID,
                DEFAULT_RESULT_SET_NAME_TEMPLATE,
                DEFAULT_LOG_PREFIX);
    }

    public static String getJsonV7Schema() {
        // Added minimum constraints and clarified descriptions
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "title": "ChunkerOptions",
                  "description": "Configuration options for chunking text.",
                  "type": "object",
                  "properties": {
                    "source_field": {
                      "description": "The field in the source data (e.g., PipeDoc) that contains the text to be chunked. Example: 'body' or 'extracted_text'.",
                      "type": "string",
                      "default": "body"
                    },
                    "chunk_size": {
                      "description": "The target size of each chunk. Must be a positive integer.",
                      "type": "integer",
                      "default": 500,
                      "minimum": 1
                    },
                    "chunk_overlap": {
                      "description": "The number of characters to overlap between consecutive chunks. Must be non-negative and ideally less than chunk_size.",
                      "type": "integer",
                      "default": 50,
                      "minimum": 0
                    },
                    "chunk_id_template": {
                      "description": "A template string for generating unique IDs for each chunk. E.g., %s_%s_chunk_%d (placeholders for stream_id, document_id, chunk_index).",
                      "type": "string",
                      "default": "%s_%s_chunk_%d"
                    },
                    "chunk_config_id": {
                      "description": "An identifier for this specific chunking configuration.",
                      "type": "string",
                      "default": "default_overlap_500_50"
                    },
                    "result_set_name_template": {
                      "description": "A template for naming the result set or grouping the chunks, possibly using pipe_step_name. E.g., %s_chunks_default_overlap_500_50.",
                      "type": "string",
                      "default": "%s_chunks_default_overlap_500_50"
                    },
                    "log_prefix": {
                      "description": "A prefix to add to log messages. Can be used for custom logging.",
                      "type": "string",
                      "default": ""
                    }
                  },
                  "required": [
                    "source_field",
                    "chunk_size",
                    "chunk_overlap",
                    "chunk_id_template",
                    "chunk_config_id",
                    "result_set_name_template",
                    "log_prefix"
                  ]
                }
                """;
    }
}
