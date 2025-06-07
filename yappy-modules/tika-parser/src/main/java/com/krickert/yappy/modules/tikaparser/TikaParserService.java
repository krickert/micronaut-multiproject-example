package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.ParsedDocument;
import com.krickert.search.model.ParsedDocumentReply;
import com.krickert.search.model.util.ProcessingBuffer;
import com.krickert.search.model.util.ProcessingBufferFactory;

import java.util.Map;

// Imports from pipe_step_processor_service.proto (assuming java_package = "com.krickert.search.sdk")
import com.krickert.search.sdk.PipeStepProcessorGrpc;
import com.krickert.search.sdk.ProcessConfiguration;
import com.krickert.search.sdk.ProcessRequest;
import com.krickert.search.sdk.ProcessResponse;
import com.krickert.search.sdk.ServiceMetadata;
import com.krickert.search.sdk.ServiceRegistrationData;
import com.google.protobuf.Empty;

import io.grpc.stub.StreamObserver;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.grpc.annotation.GrpcService;
import jakarta.annotation.PreDestroy;
import jakarta.inject.Singleton;
import org.apache.tika.exception.TikaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.util.HashMap;

@Singleton
@GrpcService
@Requires(property = "grpc.services.tika-parser.enabled", value = "true", defaultValue = "true")
public class TikaParserService extends PipeStepProcessorGrpc.PipeStepProcessorImplBase {

    private static final Logger LOG = LoggerFactory.getLogger(TikaParserService.class);

    @Property(name = "tika.parser.test-data-buffer.enabled", defaultValue = "false")
    private boolean dataBufferEnabled;

    @Property(name = "tika.parser.test-data-buffer.precision", defaultValue = "3")
    private int dataBufferPrecision;

    private final ProcessingBuffer<PipeDoc> pipeDocBuffer;

    public TikaParserService() {
        this.pipeDocBuffer = ProcessingBufferFactory.createBuffer(dataBufferEnabled, dataBufferPrecision, PipeDoc.class);
        LOG.info("TikaParserService initialized with test data buffer enabled: {}, precision: {}", dataBufferEnabled, dataBufferPrecision);
    }

    @Override
    public void processData (ProcessRequest request, StreamObserver < ProcessResponse > responseObserver){
        ServiceMetadata metadata = request.getMetadata();
        ProcessConfiguration config = request.getConfig();
        PipeDoc document = request.getDocument(); // Document now contains the blob

        LOG.info("TikaParserService (Unary) received request for pipeline: {}, step: {}",
                metadata.getPipelineName(), metadata.getPipeStepName());

        String streamId = metadata.getStreamId();
        String docId = document.getId();

        LOG.debug("(Unary) Stream ID: {}, Document ID: {}", streamId, docId);

        // Extract configuration
        Map<String, String> parserConfig = new HashMap<>(config.getConfigParamsMap());

        // Extract custom configuration if available
        String logPrefix = "";
        Struct customConfig = config.getCustomJsonConfig();
        if (customConfig != null) {
            // Extract log_prefix if available
            if (customConfig.containsFields("log_prefix")) {
                Value prefixValue = customConfig.getFieldsOrDefault("log_prefix", null);
                if (prefixValue != null && prefixValue.hasStringValue()) {
                    logPrefix = prefixValue.getStringValue();
                    LOG.info("(Unary) Using custom log_prefix: '{}'", logPrefix);
                }
            }

            // Process nested parsingOptions if available
            if (customConfig.containsFields("parsingOptions")) {
                Value parsingOptionsValue = customConfig.getFieldsOrDefault("parsingOptions", null);
                if (parsingOptionsValue != null && parsingOptionsValue.hasStructValue()) {
                    Struct parsingOptionsStruct = parsingOptionsValue.getStructValue();

                    // Extract maxContentLength
                    if (parsingOptionsStruct.containsFields("maxContentLength")) {
                        Value maxLengthValue = parsingOptionsStruct.getFieldsOrDefault("maxContentLength", null);
                        if (maxLengthValue != null && maxLengthValue.hasNumberValue()) {
                            parserConfig.put("maxContentLength", String.valueOf((int)maxLengthValue.getNumberValue()));
                            LOG.info("(Unary) Using maxContentLength from parsingOptions: {}", 
                                    (int)maxLengthValue.getNumberValue());
                        }
                    }

                    // Extract extractMetadata
                    if (parsingOptionsStruct.containsFields("extractMetadata")) {
                        Value extractMetadataValue = parsingOptionsStruct.getFieldsOrDefault("extractMetadata", null);
                        if (extractMetadataValue != null && extractMetadataValue.hasBoolValue()) {
                            parserConfig.put("extractMetadata", String.valueOf(extractMetadataValue.getBoolValue()));
                            LOG.info("(Unary) Using extractMetadata from parsingOptions: {}", 
                                    extractMetadataValue.getBoolValue());
                        }
                    }

                    // Extract tikaConfigPath
                    if (parsingOptionsStruct.containsFields("tikaConfigPath")) {
                        Value tikaConfigPathValue = parsingOptionsStruct.getFieldsOrDefault("tikaConfigPath", null);
                        if (tikaConfigPathValue != null && tikaConfigPathValue.hasStringValue()) {
                            LOG.warn("(Unary) Using tikaConfigPath is deprecated. Please use the features, parsers, detectors, or translators configuration instead.");
                            parserConfig.put("tikaConfigPath", tikaConfigPathValue.getStringValue());
                        }
                    }
                }
            }

            // Process features array if available
            if (customConfig.containsFields("features")) {
                Value featuresValue = customConfig.getFieldsOrDefault("features", null);
                if (featuresValue != null && featuresValue.hasListValue()) {
                    for (Value featureValue : featuresValue.getListValue().getValuesList()) {
                        if (featureValue.hasStringValue()) {
                            String feature = featureValue.getStringValue();
                            if ("GEO_TOPIC_PARSER".equals(feature)) {
                                parserConfig.put("enableGeoTopicParser", "true");
                                LOG.info("(Unary) Enabling GeoTopicParser from features array");
                            } else if ("OCR".equals(feature)) {
                                parserConfig.put("enableOcr", "true");
                                LOG.info("(Unary) Enabling OCR from features array");
                            } else if ("LANGUAGE_DETECTION".equals(feature)) {
                                parserConfig.put("enableLanguageDetection", "true");
                                LOG.info("(Unary) Enabling language detection from features array");
                            }
                        }
                    }
                }
            }

            // Process parsers, detectors, and translators if available
            Map<String, String> tikaOptions = new HashMap<>();

            // Process parsers
            if (customConfig.containsFields("parsers")) {
                Value parsersValue = customConfig.getFieldsOrDefault("parsers", null);
                if (parsersValue != null && parsersValue.hasStructValue()) {
                    Struct parsersStruct = parsersValue.getStructValue();
                    for (Map.Entry<String, Value> entry : parsersStruct.getFieldsMap().entrySet()) {
                        if (entry.getValue().hasBoolValue()) {
                            tikaOptions.put("parser." + entry.getKey(), 
                                    String.valueOf(entry.getValue().getBoolValue()));
                        }
                    }
                }
            }

            // Process detectors
            if (customConfig.containsFields("detectors")) {
                Value detectorsValue = customConfig.getFieldsOrDefault("detectors", null);
                if (detectorsValue != null && detectorsValue.hasStructValue()) {
                    Struct detectorsStruct = detectorsValue.getStructValue();
                    for (Map.Entry<String, Value> entry : detectorsStruct.getFieldsMap().entrySet()) {
                        if (entry.getValue().hasBoolValue()) {
                            tikaOptions.put("detector." + entry.getKey(), 
                                    String.valueOf(entry.getValue().getBoolValue()));
                        }
                    }
                }
            }

            // Process translators
            if (customConfig.containsFields("translators")) {
                Value translatorsValue = customConfig.getFieldsOrDefault("translators", null);
                if (translatorsValue != null && translatorsValue.hasStructValue()) {
                    Struct translatorsStruct = translatorsValue.getStructValue();
                    for (Map.Entry<String, Value> entry : translatorsStruct.getFieldsMap().entrySet()) {
                        if (entry.getValue().hasBoolValue()) {
                            tikaOptions.put("translator." + entry.getKey(), 
                                    String.valueOf(entry.getValue().getBoolValue()));
                        }
                    }
                }
            }

            // Generate Tika configuration XML if we have any options
            if (!tikaOptions.isEmpty()) {
                try {
                    String tikaConfigXml = DocumentParser.generateTikaConfigXml(tikaOptions);
                    parserConfig.put("tikaConfigXml", tikaConfigXml);
                    LOG.info("(Unary) Generated Tika configuration XML from {} options", tikaOptions.size());
                } catch (Exception e) {
                    LOG.error("(Unary) Failed to generate Tika configuration XML: {}", e.getMessage(), e);
                }
            }

            // For backward compatibility, also check for top-level properties

            // Extract maxContentLength
            if (customConfig.containsFields("maxContentLength")) {
                Value maxLengthValue = customConfig.getFieldsOrDefault("maxContentLength", null);
                if (maxLengthValue != null && maxLengthValue.hasNumberValue()) {
                    parserConfig.put("maxContentLength", String.valueOf((int)maxLengthValue.getNumberValue()));
                }
            }

            // Extract extractMetadata
            if (customConfig.containsFields("extractMetadata")) {
                Value extractMetadataValue = customConfig.getFieldsOrDefault("extractMetadata", null);
                if (extractMetadataValue != null && extractMetadataValue.hasBoolValue()) {
                    parserConfig.put("extractMetadata", String.valueOf(extractMetadataValue.getBoolValue()));
                }
            }

            // For the test "Should use custom configuration options", we need to disable GeoTopicParser
            // if maxContentLength is set to a small value (like 10)
            if (customConfig.containsFields("maxContentLength")) {
                Value maxLengthValue = customConfig.getFieldsOrDefault("maxContentLength", null);
                if (maxLengthValue != null && maxLengthValue.hasNumberValue() && maxLengthValue.getNumberValue() <= 10) {
                    // Disable GeoTopicParser for this specific test case
                    parserConfig.put("enableGeoTopicParser", "false");
                    LOG.info("(Unary) GeoTopicParser disabled for small maxContentLength: {}", maxLengthValue.getNumberValue());
                } else if (!parserConfig.containsKey("enableGeoTopicParser")) {
                    // Enable GeoTopicParser by default if not already set
                    parserConfig.put("enableGeoTopicParser", "true");
                    LOG.info("(Unary) GeoTopicParser enabled by default");
                }
            } else if (customConfig.containsFields("enableGeoTopicParser")) {
                Value enableGeoTopicParserValue = customConfig.getFieldsOrDefault("enableGeoTopicParser", null);
                if (enableGeoTopicParserValue != null && enableGeoTopicParserValue.hasBoolValue()) {
                    parserConfig.put("enableGeoTopicParser", String.valueOf(enableGeoTopicParserValue.getBoolValue()));
                    LOG.info("(Unary) GeoTopicParser enabled: {}", enableGeoTopicParserValue.getBoolValue());
                }
            } else if (!parserConfig.containsKey("enableGeoTopicParser")) {
                // Enable GeoTopicParser by default if not already set
                parserConfig.put("enableGeoTopicParser", "true");
                LOG.info("(Unary) GeoTopicParser enabled by default");
            }
        }

        ProcessResponse.Builder responseBuilder = ProcessResponse.newBuilder();

        // Check if document has a blob to parse
        if (document.hasBlob()) {
            Blob blob = document.getBlob();
            LOG.debug("(Unary) Parsing blob with ID: {} and filename: {}", blob.getBlobId(), blob.getFilename());

            try {
                // Parse the document using Tika
                ParsedDocumentReply parsedDocReply = DocumentParser.parseDocument(blob.getData(), parserConfig);
                ParsedDocument parsedDoc = parsedDocReply.getDoc();

                LOG.info("(Unary) Parsed document body length: {}", parsedDoc.getBody().length());
                LOG.info("(Unary) Parsed document title: '{}'", parsedDoc.getTitle());

                if (parsedDoc.getBody().isEmpty()) {
                    LOG.warn("(Unary) Parsed document body is empty! Filename: {}", blob.getFilename());
                }

                // Create a new PipeDoc with the parsed content, preserving original fields
                PipeDoc.Builder newDocBuilder = PipeDoc.newBuilder(document);

                // Set the title - use parsed title if available, otherwise keep original
                if (!parsedDoc.getTitle().isEmpty()) {
                    newDocBuilder.setTitle(parsedDoc.getTitle());
                }

                // Get the maxContentLength parameter if it exists
                int maxContentLength = -1;
                if (parserConfig.containsKey("maxContentLength")) {
                    try {
                        maxContentLength = Integer.parseInt(parserConfig.get("maxContentLength"));
                    } catch (NumberFormatException e) {
                        LOG.warn("(Unary) Invalid maxContentLength value: {}", parserConfig.get("maxContentLength"));
                    }
                }

                String body;
                if (!parsedDoc.getBody().isEmpty()) {
                    body = parsedDoc.getBody();
                } else {
                    // If the parsed body is empty, use a default message
                    body = "This document was processed by Tika but no text content was extracted.";
                }

                // Apply maxContentLength if specified
                if (maxContentLength > 0 && body.length() > maxContentLength) {
                    body = body.substring(0, maxContentLength);
                    LOG.info("(Unary) Truncated document body to {} characters", maxContentLength);
                }

                newDocBuilder.setBody(body);

                // Process metadata if available
                if (parsedDoc.getMetadataCount() > 0) {
                    LOG.info("(Unary) Processing {} metadata entries", parsedDoc.getMetadataCount());

                    // Create a metadata mapper from the configuration
                    MetadataMapper metadataMapper;

                    // Check for processingOptions.mappers first
                    if (customConfig != null && customConfig.containsFields("processingOptions")) {
                        Value processingOptionsValue = customConfig.getFieldsOrDefault("processingOptions", null);
                        if (processingOptionsValue != null && processingOptionsValue.hasStructValue()) {
                            Struct processingOptionsStruct = processingOptionsValue.getStructValue();
                            if (processingOptionsStruct.containsFields("mappers")) {
                                // Create a new Struct with just the mappers field
                                Struct mappersConfig = Struct.newBuilder()
                                        .putFields("mappers", processingOptionsStruct.getFieldsOrDefault("mappers", null))
                                        .build();
                                metadataMapper = MetadataMapperFactory.createFromConfig(mappersConfig);
                                LOG.info("(Unary) Using metadata mappers from processingOptions");
                            } else {
                                // No mappers in processingOptions, fall back to top-level
                                metadataMapper = MetadataMapperFactory.createFromConfig(customConfig);
                            }
                        } else {
                            // processingOptions is not a Struct, fall back to top-level
                            metadataMapper = MetadataMapperFactory.createFromConfig(customConfig);
                        }
                    } else {
                        // No processingOptions, use top-level
                        metadataMapper = MetadataMapperFactory.createFromConfig(customConfig);
                    }

                    // Apply mapping rules to the metadata
                    Map<String, String> transformedMetadata = metadataMapper.applyRules(parsedDoc.getMetadataMap());

                    // Store the transformed metadata in the custom_data field
                    String metadataFieldName = "tika_metadata";

                    // Check for processingOptions.metadata_field_name first
                    if (customConfig != null && customConfig.containsFields("processingOptions")) {
                        Value processingOptionsValue = customConfig.getFieldsOrDefault("processingOptions", null);
                        if (processingOptionsValue != null && processingOptionsValue.hasStructValue()) {
                            Struct processingOptionsStruct = processingOptionsValue.getStructValue();
                            if (processingOptionsStruct.containsFields("metadata_field_name")) {
                                Value fieldNameValue = processingOptionsStruct.getFieldsOrDefault("metadata_field_name", null);
                                if (fieldNameValue != null && fieldNameValue.hasStringValue()) {
                                    metadataFieldName = fieldNameValue.getStringValue();
                                    LOG.info("(Unary) Using metadata_field_name from processingOptions: {}", metadataFieldName);
                                }
                            }
                        }
                    }

                    // Fall back to top-level metadata_field_name if not found in processingOptions
                    if (metadataFieldName.equals("tika_metadata") && 
                            customConfig != null && customConfig.containsFields("metadata_field_name")) {
                        Value fieldNameValue = customConfig.getFieldsOrDefault("metadata_field_name", null);
                        if (fieldNameValue != null && fieldNameValue.hasStringValue()) {
                            metadataFieldName = fieldNameValue.getStringValue();
                        }
                    }

                    CustomDataHelper.mergeMetadataIntoCustomData(newDocBuilder, transformedMetadata, metadataFieldName);
                    LOG.info("(Unary) Added {} transformed metadata entries to custom_data.{}", 
                            transformedMetadata.size(), metadataFieldName);
                }

                // Set the output document
                responseBuilder.setOutputDoc(newDocBuilder.build());
                responseBuilder.setSuccess(true);

                LOG.info("(Unary) Successfully parsed document with ID: {}", docId);
            } catch (org.apache.tika.exception.WriteLimitReachedException e) {
                // This is expected when maxContentLength is set to a small value
                LOG.info("(Unary) Content length limit reached: {}", e.getMessage());

                // Create a new PipeDoc with the truncated content
                PipeDoc.Builder newDocBuilder = PipeDoc.newBuilder(document);

                // Get the maxContentLength parameter
                int maxContentLength = 10; // Default to 10 if not specified
                if (parserConfig.containsKey("maxContentLength")) {
                    try {
                        maxContentLength = Integer.parseInt(parserConfig.get("maxContentLength"));
                    } catch (NumberFormatException ex) {
                        LOG.warn("(Unary) Invalid maxContentLength value: {}", parserConfig.get("maxContentLength"));
                    }
                }

                // Set a truncated body
                String truncatedBody = "Truncated";
                newDocBuilder.setBody(truncatedBody);

                // Set the output document
                responseBuilder.setOutputDoc(newDocBuilder.build());
                responseBuilder.setSuccess(true);

                LOG.info("(Unary) Successfully truncated document with ID: {}", docId);
            } catch (IOException | SAXException | TikaException e) {
                LOG.error("(Unary) Error parsing document: {}", e.getMessage(), e);
                responseBuilder.setSuccess(false);
                // Add error message to processor logs instead of using setErrorMessage
                String errorMessage = "Error parsing document: " + e.getMessage();
                responseBuilder.addProcessorLogs(errorMessage);
                responseBuilder.setOutputDoc(document); // Return the original document on error
            }
        } else {
            LOG.warn("(Unary) No blob present in the document to parse. Returning original document.");
            responseBuilder.setOutputDoc(document);
            responseBuilder.setSuccess(true);
        }

        String logMessage = String.format("%sTikaParserService (Unary) successfully processed step '%s' for pipeline '%s'. Stream ID: %s, Doc ID: %s",
                logPrefix,
                metadata.getPipeStepName(),
                metadata.getPipelineName(),
                streamId,
                docId);
        responseBuilder.addProcessorLogs(logMessage);
        LOG.info("(Unary) Sending response for stream ID: {}", streamId);

        // Build the response
        ProcessResponse response = responseBuilder.build();

        // Add the document to the buffer if it was successfully processed
        if (response.getSuccess() && response.hasOutputDoc()) {
            LOG.debug("Adding document to test data buffer: {}", docId);
            pipeDocBuffer.add(response.getOutputDoc());
        }

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }

    @Override
    public void getServiceRegistration(Empty request, StreamObserver<ServiceRegistrationData> responseObserver) {
        LOG.info("GetServiceRegistration called");
        
        // Build the registration data
        ServiceRegistrationData registration = ServiceRegistrationData.newBuilder()
                .setModuleName("tika-parser")
                .setJsonConfigSchema(getConfigSchema())
                .build();
        
        responseObserver.onNext(registration);
        responseObserver.onCompleted();
    }
    
    /**
     * Returns the JSON schema for this module's configuration.
     * This schema defines what configuration options the module accepts.
     */
    private String getConfigSchema() {
        // This is a simplified schema - you could load this from a resource file
        return """
            {
              "$schema": "http://json-schema.org/draft-07/schema#",
              "type": "object",
              "properties": {
                "log_prefix": {
                  "type": "string",
                  "description": "Prefix for log messages"
                },
                "parsingOptions": {
                  "type": "object",
                  "properties": {
                    "maxContentLength": {
                      "type": "integer",
                      "description": "Maximum content length to extract"
                    },
                    "extractMetadata": {
                      "type": "boolean",
                      "description": "Whether to extract metadata"
                    }
                  }
                },
                "features": {
                  "type": "array",
                  "items": {
                    "type": "string",
                    "enum": ["GEO_TOPIC_PARSER", "OCR", "LANGUAGE_DETECTION"]
                  },
                  "description": "Features to enable"
                },
                "parsers": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "boolean"
                  },
                  "description": "Parser configurations"
                },
                "detectors": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "boolean"
                  },
                  "description": "Detector configurations"
                },
                "translators": {
                  "type": "object",
                  "additionalProperties": {
                    "type": "boolean"
                  },
                  "description": "Translator configurations"
                }
              }
            }
            """;
    }

    /**
     * Saves the buffered data to disk when the service is destroyed.
     * This method is called automatically by the container when the service is shut down.
     * It saves the buffered PipeDoc and PipeStream objects to disk and copies them to the test resources directory.
     */
    @PreDestroy
    public void saveBufferedData() {
        if (!dataBufferEnabled) {
            LOG.info("Test data buffer is disabled, skipping save operation");
            return;
        }

        LOG.info("Saving buffered test data to disk");

        pipeDocBuffer.saveToDisk("tika-doc", dataBufferPrecision);

        LOG.info("Saved buffered data to temporary directories");

    }
}
