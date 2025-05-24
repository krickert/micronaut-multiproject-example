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
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

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
        boolean enableGeoTopicParser = getBooleanConfig(config, "enableGeoTopicParser", false);

        // Check if we need to disable EMF parser for problematic file types
        boolean disableEmfParser = false;
        if (config.containsKey("filename")) {
            String filename = config.get("filename").toLowerCase();
            if (filename.endsWith(".ppt") || filename.endsWith(".doc")) {
                disableEmfParser = true;
                LOG.info("Disabling EMF parser for file: {}", filename);
            }
        }

        // Create the appropriate parser
        Parser parser;
        if (disableEmfParser) {
            try {
                String customConfig = createCustomParserConfig();
                try (InputStream is = new ByteArrayInputStream(customConfig.getBytes())) {
                    TikaConfig tikaConfig = new TikaConfig(is);
                    parser = new AutoDetectParser(tikaConfig);
                }
            } catch (ParserConfigurationException | TransformerException e) {
                LOG.error("Failed to create custom parser configuration: {}", e.getMessage(), e);
                LOG.info("Falling back to default Tika configuration");
                parser = new AutoDetectParser();
            }
        } else if (enableGeoTopicParser) {
            LOG.info("Using Tika configuration with GeoTopicParser enabled");
            try {
                String geoTopicConfig = createGeoTopicParserConfig();
                try (InputStream is = new ByteArrayInputStream(geoTopicConfig.getBytes())) {
                    TikaConfig tikaConfig = new TikaConfig(is);
                    parser = new AutoDetectParser(tikaConfig);
                }
            } catch (ParserConfigurationException | TransformerException e) {
                LOG.error("Failed to create GeoTopicParser configuration: {}", e.getMessage(), e);
                LOG.info("Falling back to default Tika configuration");
                parser = new AutoDetectParser();
            }
        } else {
            LOG.info("Using default Tika configuration");
            parser = new AutoDetectParser();
        }

        // Set up the content handler with the specified max content length
        // Use -1 for unlimited content length if not specified
        BodyContentHandler handler = maxContentLength > 0 ? 
                new BodyContentHandler(maxContentLength) : 
                new BodyContentHandler(-1); // -1 means unlimited

        // Set up metadata and parse context
        Metadata metadata = new Metadata();
        ParseContext parseContext = new ParseContext();
        parseContext.set(Parser.class, parser);

        // Parse the document
        try (InputStream stream = new ByteArrayInputStream(content.toByteArray())) {
            // Add filename to metadata if available
            if (config.containsKey("filename")) {
                metadata.set("resourceName", config.get("filename"));
            }

            parser.parse(stream, handler, metadata, parseContext);
        }

        // Extract title and body
        String title = cleanUpText(metadata.get("dc:title"));
        String body = cleanUpText(handler.toString());

        // If body is empty, try to get content from other metadata fields
        if (body.isEmpty()) {
            // Try to get content from the content metadata field
            String contentFromMetadata = cleanUpText(metadata.get("content"));
            if (!contentFromMetadata.isEmpty()) {
                body = contentFromMetadata;
            }
        }

        // If still empty and it's a text file, use the content directly
        if (body.isEmpty() && metadata.get("Content-Type") != null && 
                metadata.get("Content-Type").startsWith("text/")) {
            try (InputStream stream = new ByteArrayInputStream(content.toByteArray())) {
                body = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            }
        }

        LOG.debug("Parsed document title: {}", title);
        LOG.debug("Parsed document body length: {}", body.length());
        LOG.debug("Content type: {}", metadata.get("Content-Type"));

        // If body is still empty, use a default message
        if (body.isEmpty()) {
            body = "This document was processed by Tika but no text content was extracted.";
            LOG.warn("No text content extracted from document. Using default message.");
        }

        // Build the parsed document
        ParsedDocument.Builder docBuilder = ParsedDocument.newBuilder()
                .setBody(body);

        if (title != null && !title.isEmpty()) {
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
     * Creates a custom Tika configuration XML that disables problematic parsers.
     * 
     * @return XML string representation of the custom Tika configuration
     * @throws ParserConfigurationException if there's an error creating the XML document
     * @throws TransformerException if there's an error transforming the XML document to a string
     */
    public static String createCustomParserConfig() 
            throws ParserConfigurationException, TransformerException {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

        // Root element
        Document doc = docBuilder.newDocument();
        Element rootElement = doc.createElement("properties");
        doc.appendChild(rootElement);

        // Add parser options
        Element parsers = doc.createElement("parsers");
        rootElement.appendChild(parsers);

        // Disable EMF Parser
        Element emfParser = doc.createElement("parser");
        emfParser.setAttribute("class", "org.apache.tika.parser.microsoft.EMFParser");
        emfParser.setAttribute("enabled", "false");
        parsers.appendChild(emfParser);

        // Write the XML to a string
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return writer.toString();
    }

    /**
     * Creates a Tika configuration XML with GeoTopicParser enabled.
     * 
     * @return XML string representation of the Tika configuration with GeoTopicParser
     * @throws ParserConfigurationException if there's an error creating the XML document
     * @throws TransformerException if there's an error transforming the XML document to a string
     */
    public static String createGeoTopicParserConfig() 
            throws ParserConfigurationException, TransformerException {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

        // Root element
        Document doc = docBuilder.newDocument();
        Element rootElement = doc.createElement("properties");
        doc.appendChild(rootElement);

        // Add parser options
        Element parsers = doc.createElement("parsers");
        rootElement.appendChild(parsers);

        // Add GeoTopicParser
        Element geoTopicParser = doc.createElement("parser");
        geoTopicParser.setAttribute("class", "org.apache.tika.parser.geo.topic.GeoTopicParser");
        geoTopicParser.setAttribute("enabled", "true");
        parsers.appendChild(geoTopicParser);

        // Write the XML to a string
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return writer.toString();
    }

    /**
     * Generates a Tika configuration XML from the provided options.
     * 
     * @param options Map of configuration options
     * @return XML string representation of the Tika configuration
     * @throws ParserConfigurationException if there's an error creating the XML document
     * @throws TransformerException if there's an error transforming the XML document to a string
     */
    public static String generateTikaConfigXml(Map<String, String> options) 
            throws ParserConfigurationException, TransformerException {
        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = docFactory.newDocumentBuilder();

        // Root element
        Document doc = docBuilder.newDocument();
        Element rootElement = doc.createElement("properties");
        doc.appendChild(rootElement);

        // Add parser options
        Element parsers = doc.createElement("parsers");
        rootElement.appendChild(parsers);

        // Add parser options from the configuration
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey().startsWith("parser.")) {
                String parserName = entry.getKey().substring("parser.".length());
                Element parser = doc.createElement("parser");
                parser.setAttribute("class", parserName);
                parser.setAttribute("enabled", entry.getValue());
                parsers.appendChild(parser);
            }
        }

        // Add detector options
        Element detectors = doc.createElement("detectors");
        rootElement.appendChild(detectors);

        // Add detector options from the configuration
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey().startsWith("detector.")) {
                String detectorName = entry.getKey().substring("detector.".length());
                Element detector = doc.createElement("detector");
                detector.setAttribute("class", detectorName);
                detector.setAttribute("enabled", entry.getValue());
                detectors.appendChild(detector);
            }
        }

        // Add translator options
        Element translators = doc.createElement("translators");
        rootElement.appendChild(translators);

        // Add translator options from the configuration
        for (Map.Entry<String, String> entry : options.entrySet()) {
            if (entry.getKey().startsWith("translator.")) {
                String translatorName = entry.getKey().substring("translator.".length());
                Element translator = doc.createElement("translator");
                translator.setAttribute("class", translatorName);
                translator.setAttribute("enabled", entry.getValue());
                translators.appendChild(translator);
            }
        }

        // Write the XML to a string
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));

        return writer.toString();
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
