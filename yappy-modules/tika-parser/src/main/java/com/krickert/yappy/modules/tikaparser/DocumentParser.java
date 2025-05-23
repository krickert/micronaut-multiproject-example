package com.krickert.yappy.modules.tikaparser;

import com.google.protobuf.ByteString;
import com.krickert.search.model.ParsedDocument;
import com.krickert.search.model.ParsedDocumentReply;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Utility class for parsing documents using Apache Tika.
 */
public class DocumentParser {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentParser.class);
    
    /**
     * Parses a document and returns the parsed information in a document reply.
     *
     * @param content The content of the document to parse.
     * @param config The configuration for the parser.
     * @return A ParsedDocumentReply object containing the parsed title and body of the document.
     * @throws IOException if an I/O error occurs while parsing the document.
     * @throws SAXException if a SAX error occurs while parsing the document.
     * @throws TikaException if a Tika error occurs while parsing the document.
     */
    public static ParsedDocumentReply parseDocument(ByteString content, Map<String, String> config) 
            throws IOException, SAXException, TikaException {
        // Get configuration options or use defaults
        int maxContentLength = getIntConfig(config, "maxContentLength", -1); // -1 means no limit
        boolean extractMetadata = getBooleanConfig(config, "extractMetadata", true);
        String customTikaConfigPath = config.getOrDefault("tikaConfigPath", null);
        
        // Create the appropriate parser
        Parser parser;
        if (customTikaConfigPath != null) {
            LOG.info("Using custom Tika configuration from: {}", customTikaConfigPath);
            TikaConfig tikaConfig = new TikaConfig(customTikaConfigPath);
            parser = new AutoDetectParser(tikaConfig);
        } else {
            LOG.info("Using default Tika configuration");
            parser = new AutoDetectParser();
        }
        
        // Set up the content handler with the specified max content length
        BodyContentHandler handler = new BodyContentHandler(maxContentLength);
        
        // Set up metadata and parse context
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        
        // Parse the document
        try (InputStream stream = new ByteArrayInputStream(content.toByteArray())) {
            parser.parse(stream, handler, metadata, parseContext);
        }
        
        // Extract title and body
        String title = cleanUpText(metadata.get("dc:title"));
        String body = cleanUpText(handler.toString());
        
        // Build the parsed document
        ParsedDocument.Builder docBuilder = ParsedDocument.newBuilder()
                .setBody(body);
        
        if (title != null) {
            docBuilder.setTitle(title);
        }
        
        // Add metadata if requested
        if (extractMetadata) {
            for (String name : metadata.names()) {
                String value = metadata.get(name);
                if (value != null) {
                    docBuilder.putMetadata(name, value);
                }
            }
        }
        
        return ParsedDocumentReply.newBuilder()
                .setDoc(docBuilder.build())
                .build();
    }
    
    /**
     * Cleans up text by removing extra whitespace and null values.
     *
     * @param text The text to clean up.
     * @return The cleaned up text, or an empty string if the input was null.
     */
    private static String cleanUpText(String text) {
        if (text == null) {
            return "";
        }
        return text.trim();
    }
    
    /**
     * Gets an integer configuration value, or the default if not present or invalid.
     *
     * @param config The configuration map.
     * @param key The key to look up.
     * @param defaultValue The default value to use if the key is not present or invalid.
     * @return The configuration value as an integer.
     */
    private static int getIntConfig(Map<String, String> config, String key, int defaultValue) {
        String value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warn("Invalid integer value for {}: {}. Using default: {}", key, value, defaultValue);
            return defaultValue;
        }
    }
    
    /**
     * Gets a boolean configuration value, or the default if not present.
     *
     * @param config The configuration map.
     * @param key The key to look up.
     * @param defaultValue The default value to use if the key is not present.
     * @return The configuration value as a boolean.
     */
    private static boolean getBooleanConfig(Map<String, String> config, String key, boolean defaultValue) {
        String value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value);
    }
}