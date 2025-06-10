package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.JsonFormat;
import com.krickert.search.model.PipeDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Utility class for working with PipeDoc's custom_data field.
 * 
 * <p>This helper class provides methods to merge metadata into and extract metadata from
 * the Protocol Buffers Struct field (custom_data) in PipeDoc messages. It handles the
 * conversion between Java Maps and Protocol Buffer Struct types.</p>
 */
public class CustomDataHelper {
    private static final Logger LOG = LoggerFactory.getLogger(CustomDataHelper.class);
    
    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private CustomDataHelper() {
        // Utility class
    }

    /**
     * Merges metadata into a PipeDoc's custom_data field.
     * If the PipeDoc already has custom_data, the metadata will be added to it.
     * If the PipeDoc doesn't have custom_data, a new Struct will be created.
     *
     * @param docBuilder The PipeDoc.Builder to update
     * @param metadata The metadata to add
     * @param metadataFieldName The field name to use for the metadata in custom_data
     * @return The updated PipeDoc.Builder
     */
    public static PipeDoc.Builder mergeMetadataIntoCustomData(
            PipeDoc.Builder docBuilder, 
            Map<String, String> metadata,
            String metadataFieldName) {
        
        if (metadata == null || metadata.isEmpty()) {
            LOG.debug("No metadata to merge into custom_data");
            return docBuilder;
        }
        
        // Create a builder for the custom_data Struct
        Struct.Builder customDataBuilder;
        
        // If the document already has custom_data, use it as a base
        if (docBuilder.hasCustomData()) {
            customDataBuilder = docBuilder.getCustomData().toBuilder();
        } else {
            customDataBuilder = Struct.newBuilder();
        }
        
        // Create a Struct for the metadata
        Struct.Builder metadataStructBuilder = Struct.newBuilder();
        
        // Add each metadata entry to the metadata Struct
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            metadataStructBuilder.putFields(
                entry.getKey(),
                Value.newBuilder().setStringValue(entry.getValue()).build()
            );
        }
        
        // Add the metadata Struct to the custom_data Struct
        customDataBuilder.putFields(
            metadataFieldName,
            Value.newBuilder().setStructValue(metadataStructBuilder.build()).build()
        );
        
        // Set the updated custom_data on the document
        docBuilder.setCustomData(customDataBuilder.build());
        
        LOG.debug("Merged {} metadata entries into custom_data.{}", metadata.size(), metadataFieldName);
        
        return docBuilder;
    }
    
    /**
     * Extracts metadata from a PipeDoc's custom_data field.
     *
     * @param doc The PipeDoc to extract from
     * @param metadataFieldName The field name of the metadata in custom_data
     * @return The extracted metadata, or an empty map if not found
     */
    public static Map<String, String> extractMetadataFromCustomData(PipeDoc doc, String metadataFieldName) {
        Map<String, String> result = new HashMap<>();
        
        if (!doc.hasCustomData()) {
            LOG.debug("Document has no custom_data");
            return result;
        }
        
        Struct customData = doc.getCustomData();
        if (!customData.containsFields(metadataFieldName)) {
            LOG.debug("custom_data does not contain field: {}", metadataFieldName);
            return result;
        }
        
        Value metadataValue = customData.getFieldsOrDefault(metadataFieldName, null);
        if (metadataValue == null || !metadataValue.hasStructValue()) {
            LOG.debug("Field {} is not a Struct", metadataFieldName);
            return result;
        }
        
        Struct metadataStruct = metadataValue.getStructValue();
        
        // Extract each metadata entry
        for (Map.Entry<String, Value> entry : metadataStruct.getFieldsMap().entrySet()) {
            Value value = entry.getValue();
            if (value.hasStringValue()) {
                result.put(entry.getKey(), value.getStringValue());
            }
        }
        
        LOG.debug("Extracted {} metadata entries from custom_data.{}", result.size(), metadataFieldName);
        
        return result;
    }
}