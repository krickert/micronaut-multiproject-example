package com.krickert.yappy.modules.embedder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration options for the embedder service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EmbedderOptions(
        @JsonProperty("embedding_models") List<EmbeddingModel> embeddingModels,
        @JsonProperty("check_chunks") Boolean checkChunks,
        @JsonProperty("check_document_fields") Boolean checkDocumentFields,
        @JsonProperty("document_fields") List<String> documentFields,
        @JsonProperty("custom_field_mappings") List<FieldMapping> customFieldMappings,
        @JsonProperty("process_keywords") Boolean processKeywords,
        @JsonProperty("keyword_ngram_sizes") List<Integer> keywordNgramSizes,
        @JsonProperty("max_token_size") Integer maxTokenSize,
        @JsonProperty("log_prefix") String logPrefix,
        @JsonProperty("result_set_name_template") String resultSetNameTemplate
) {
    // Default values
    public static final List<EmbeddingModel> DEFAULT_EMBEDDING_MODELS = List.of(EmbeddingModel.ALL_MINILM_L6_V2);
    public static final boolean DEFAULT_CHECK_CHUNKS = true;
    public static final boolean DEFAULT_CHECK_DOCUMENT_FIELDS = true;
    public static final List<String> DEFAULT_DOCUMENT_FIELDS = List.of("body", "title");
    public static final List<FieldMapping> DEFAULT_CUSTOM_FIELD_MAPPINGS = List.of();
    public static final boolean DEFAULT_PROCESS_KEYWORDS = true;
    public static final List<Integer> DEFAULT_KEYWORD_NGRAM_SIZES = List.of(1); // Default to just using all keywords together
    public static final int DEFAULT_MAX_TOKEN_SIZE = 512;
    public static final String DEFAULT_LOG_PREFIX = "";
    public static final String DEFAULT_RESULT_SET_NAME_TEMPLATE = "%s_embeddings_%s"; // e.g., pipeStepName, embeddingModelId

    /**
     * Default constructor with default values.
     */
    public EmbedderOptions() {
        this(DEFAULT_EMBEDDING_MODELS,
                DEFAULT_CHECK_CHUNKS,
                DEFAULT_CHECK_DOCUMENT_FIELDS,
                DEFAULT_DOCUMENT_FIELDS,
                DEFAULT_CUSTOM_FIELD_MAPPINGS,
                DEFAULT_PROCESS_KEYWORDS,
                DEFAULT_KEYWORD_NGRAM_SIZES,
                DEFAULT_MAX_TOKEN_SIZE,
                DEFAULT_LOG_PREFIX,
                DEFAULT_RESULT_SET_NAME_TEMPLATE);
    }

    /**
     * Returns a JSON schema for the EmbedderOptions.
     *
     * @return a JSON schema string
     */
    public static String getJsonV7Schema() {
        return """
                {
                  "$schema": "http://json-schema.org/draft-07/schema#",
                  "title": "EmbedderOptions",
                  "description": "Configuration options for the embedder service.",
                  "type": "object",
                  "properties": {
                    "embedding_models": {
                      "description": "List of embedding models to use. Each model will generate its own set of embeddings.",
                      "type": "array",
                      "items": {
                        "type": "string",
                        "enum": ["ALL_MINILM_L6_V2", "ALL_MPNET_BASE_V2", "ALL_DISTILROBERTA_V1", "PARAPHRASE_MINILM_L3_V2", "PARAPHRASE_MULTILINGUAL_MINILM_L12_V2", "E5_SMALL_V2", "E5_LARGE_V2", "MULTI_QA_MINILM_L6_COS_V1"]
                      },
                      "default": ["ALL_MINILM_L6_V2"]
                    },
                    "check_chunks": {
                      "description": "Whether to check for and process chunks in the document.",
                      "type": "boolean",
                      "default": true
                    },
                    "check_document_fields": {
                      "description": "Whether to check and process document fields if chunks are not present.",
                      "type": "boolean",
                      "default": true
                    },
                    "document_fields": {
                      "description": "List of document fields to process if chunks are not present.",
                      "type": "array",
                      "items": {
                        "type": "string"
                      },
                      "default": ["body", "title"]
                    },
                    "custom_field_mappings": {
                      "description": "Custom field mappings for embedding specific fields.",
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "source_field": {
                            "type": "string",
                            "description": "The source field to get the text from."
                          },
                          "target_field": {
                            "type": "string",
                            "description": "The target field to store the embedding in."
                          }
                        },
                        "required": ["source_field", "target_field"]
                      },
                      "default": []
                    },
                    "process_keywords": {
                      "description": "Whether to process keywords in the document.",
                      "type": "boolean",
                      "default": true
                    },
                    "keyword_ngram_sizes": {
                      "description": "List of n-gram sizes to use for keywords. For example, [1, 2, 3] would create embeddings for individual keywords, pairs of keywords, and triplets of keywords.",
                      "type": "array",
                      "items": {
                        "type": "integer",
                        "minimum": 1
                      },
                      "default": [1]
                    },
                    "max_token_size": {
                      "description": "Maximum token size for text to be embedded.",
                      "type": "integer",
                      "minimum": 1,
                      "default": 512
                    },
                    "log_prefix": {
                      "description": "Prefix to add to log messages.",
                      "type": "string",
                      "default": ""
                    },
                    "result_set_name_template": {
                      "description": "Template for naming the result set. Placeholders: %s for pipe step name, %s for embedding model ID.",
                      "type": "string",
                      "default": "%s_embeddings_%s"
                    }
                  }
                }
                """;
    }

    /**
     * Represents a mapping from a source field to a target field for embedding.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldMapping(
            @JsonProperty("source_field") String sourceField,
            @JsonProperty("target_field") String targetField
    ) {
    }
}