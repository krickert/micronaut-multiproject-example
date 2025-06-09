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
 * 
 * <p>This class provides static methods to parse various document formats using Apache Tika.
 * It supports custom parser configurations, metadata extraction, and content length limits.
 * The parser can handle various document formats including PDF, Microsoft Office documents,
 * HTML, XML, and plain text files.</p>
 * 
 * <p>The parser can be configured to disable specific parsers (like EMF) for problematic
 * file types, or enable special parsers (like GeoTopicParser) for enhanced functionality.</p>
 */
public class DocumentParser {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentParser.class);

    /**
     * Private constructor to prevent instantiation of this utility class.
     */
    private DocumentParser() {
        // Utility class
    }

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

        // Build the parsed document
        ParsedDocument parsedDoc = docBuilder.build();

        // Apply post-processing based on document type
        parsedDoc = postProcessParsedDocument(parsedDoc, config);

        return ParsedDocumentReply.newBuilder()
                .setDoc(parsedDoc)
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
     * Post-processes a parsed document based on its content type.
     * This method applies document type-specific mappings for null fields.
     *
     * @param parsedDoc The parsed document to post-process.
     * @param config The configuration for the parser.
     * @return The post-processed document.
     */
    private static ParsedDocument postProcessParsedDocument(ParsedDocument parsedDoc, Map<String, String> config) {
        // If both title and body are non-empty, no need for post-processing
        if (!parsedDoc.getTitle().isEmpty() && !parsedDoc.getBody().isEmpty()) {
            return parsedDoc;
        }

        // Get the content type from metadata
        String contentType = parsedDoc.getMetadataOrDefault("Content-Type", "");
        if (contentType.isEmpty()) {
            // Try to infer content type from filename if available
            if (config.containsKey("filename")) {
                String filename = config.get("filename").toLowerCase();
                contentType = inferContentTypeFromFilename(filename);
            }
        }

        LOG.debug("Post-processing document with content type: {}", contentType);

        // Create a builder from the parsed document
        ParsedDocument.Builder builder = parsedDoc.toBuilder();

        // Apply document type-specific processing
        if (contentType.startsWith("application/pdf")) {
            processPdfDocument(parsedDoc, builder);
        } else if (contentType.startsWith("application/vnd.openxmlformats-officedocument.presentationml") || 
                   contentType.startsWith("application/vnd.ms-powerpoint") ||
                   contentType.startsWith("application/vnd.oasis.opendocument.presentation")) {
            processPresentationDocument(parsedDoc, builder);
        } else if (contentType.startsWith("application/vnd.openxmlformats-officedocument.wordprocessingml") || 
                   contentType.startsWith("application/msword") ||
                   contentType.startsWith("application/vnd.oasis.opendocument.text") ||
                   contentType.startsWith("application/rtf")) {
            processWordDocument(parsedDoc, builder);
        } else if (contentType.startsWith("application/vnd.openxmlformats-officedocument.spreadsheetml") || 
                   contentType.startsWith("application/vnd.ms-excel") ||
                   contentType.startsWith("application/vnd.oasis.opendocument.spreadsheet") ||
                   contentType.startsWith("text/csv")) {
            processSpreadsheetDocument(parsedDoc, builder);
        } else if (contentType.startsWith("text/html")) {
            processHtmlDocument(parsedDoc, builder);
        } else if (contentType.startsWith("image/")) {
            processImageDocument(parsedDoc, builder);
        } else if (contentType.startsWith("video/")) {
            processVideoDocument(parsedDoc, builder);
        } else if (contentType.startsWith("text/plain")) {
            processTextDocument(parsedDoc, builder);
        } else if (contentType.startsWith("application/json")) {
            processJsonDocument(parsedDoc, builder);
        }

        return builder.build();
    }

    /**
     * Infers the content type from a filename.
     *
     * @param filename The filename to infer the content type from.
     * @return The inferred content type, or an empty string if it couldn't be inferred.
     */
    private static String inferContentTypeFromFilename(String filename) {
        if (filename.endsWith(".pdf")) {
            return "application/pdf";
        } else if (filename.endsWith(".pptx")) {
            return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        } else if (filename.endsWith(".ppt")) {
            return "application/vnd.ms-powerpoint";
        } else if (filename.endsWith(".odp")) {
            return "application/vnd.oasis.opendocument.presentation";
        } else if (filename.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (filename.endsWith(".doc")) {
            return "application/msword";
        } else if (filename.endsWith(".odt")) {
            return "application/vnd.oasis.opendocument.text";
        } else if (filename.endsWith(".rtf")) {
            return "application/rtf";
        } else if (filename.endsWith(".xlsx")) {
            return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        } else if (filename.endsWith(".xls")) {
            return "application/vnd.ms-excel";
        } else if (filename.endsWith(".ods")) {
            return "application/vnd.oasis.opendocument.spreadsheet";
        } else if (filename.endsWith(".csv")) {
            return "text/csv";
        } else if (filename.endsWith(".html") || filename.endsWith(".htm")) {
            return "text/html";
        } else if (filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return "image/jpeg";
        } else if (filename.endsWith(".png")) {
            return "image/png";
        } else if (filename.endsWith(".gif")) {
            return "image/gif";
        } else if (filename.endsWith(".mp4")) {
            return "video/mp4";
        } else if (filename.endsWith(".txt")) {
            return "text/plain";
        } else if (filename.endsWith(".json")) {
            return "application/json";
        }
        return "";
    }

    /**
     * Processes a PDF document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processPdfDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // If still empty, try to extract from the first line of the body
            if (title.isEmpty() && !parsedDoc.getBody().isEmpty()) {
                String body = parsedDoc.getBody();
                int endOfFirstLine = body.indexOf('\n');
                if (endOfFirstLine > 0 && endOfFirstLine < 100) {  // Reasonable title length
                    title = body.substring(0, endOfFirstLine).trim();
                }
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set PDF title from metadata or first line: {}", title);
            }
        }
    }

    /**
     * Processes a presentation document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processPresentationDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For presentations, the first slide title is often a good title
            if (title.isEmpty() && !parsedDoc.getBody().isEmpty()) {
                String body = parsedDoc.getBody();
                int endOfFirstLine = body.indexOf('\n');
                if (endOfFirstLine > 0) {
                    title = body.substring(0, endOfFirstLine).trim();
                }
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set presentation title from metadata or first slide: {}", title);
            }
        }

        // For presentations, if body is empty, try to extract text from slide notes or content
        if (parsedDoc.getBody().isEmpty()) {
            String slideContent = parsedDoc.getMetadataOrDefault("slide-content", "");
            if (!slideContent.isEmpty()) {
                builder.setBody(slideContent);
                LOG.debug("Set presentation body from slide content");
            }
        }
    }

    /**
     * Processes a word processing document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processWordDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For word documents, the first heading is often a good title
            if (title.isEmpty() && !parsedDoc.getBody().isEmpty()) {
                String body = parsedDoc.getBody();
                int endOfFirstLine = body.indexOf('\n');
                if (endOfFirstLine > 0 && endOfFirstLine < 100) {  // Reasonable title length
                    title = body.substring(0, endOfFirstLine).trim();
                }
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set word document title from metadata or first heading: {}", title);
            }
        }
    }

    /**
     * Processes a spreadsheet document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processSpreadsheetDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For spreadsheets, the sheet name or workbook name can be a good title
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("sheetName", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("workbookName", "");
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set spreadsheet title from metadata: {}", title);
            }
        }

        // For spreadsheets, if body is empty, try to extract text from cell data
        if (parsedDoc.getBody().isEmpty()) {
            String cellData = parsedDoc.getMetadataOrDefault("cell-data", "");
            if (!cellData.isEmpty()) {
                builder.setBody(cellData);
                LOG.debug("Set spreadsheet body from cell data");
            }
        }
    }

    /**
     * Processes an HTML document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processHtmlDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For HTML, the HTML title tag is a good source
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("og:title", "");
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set HTML title from metadata: {}", title);
            }
        }
    }

    /**
     * Processes an image document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processImageDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For images, EXIF data can provide a title
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Exif.Image.ImageDescription", "");
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set image title from metadata: {}", title);
            }
        }

        // For images, if body is empty, try to use image description or alt text
        if (parsedDoc.getBody().isEmpty()) {
            String description = parsedDoc.getMetadataOrDefault("Exif.Image.ImageDescription", "");
            if (description.isEmpty()) {
                description = parsedDoc.getMetadataOrDefault("alt", "");
            }
            if (!description.isEmpty()) {
                builder.setBody(description);
                LOG.debug("Set image body from description");
            } else {
                // If no description, create a basic one with image dimensions
                String width = parsedDoc.getMetadataOrDefault("Image Width", "");
                String height = parsedDoc.getMetadataOrDefault("Image Height", "");
                if (!width.isEmpty() && !height.isEmpty()) {
                    String imageInfo = "Image dimensions: " + width + " x " + height;
                    builder.setBody(imageInfo);
                    LOG.debug("Set image body from dimensions");
                }
            }
        }
    }

    /**
     * Processes a video document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processVideoDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set video title from metadata: {}", title);
            }
        }

        // For videos, if body is empty, try to use video description or create one with video info
        if (parsedDoc.getBody().isEmpty()) {
            String description = parsedDoc.getMetadataOrDefault("description", "");
            if (!description.isEmpty()) {
                builder.setBody(description);
                LOG.debug("Set video body from description");
            } else {
                // If no description, create a basic one with video dimensions and duration
                StringBuilder videoInfo = new StringBuilder();
                String width = parsedDoc.getMetadataOrDefault("width", "");
                String height = parsedDoc.getMetadataOrDefault("height", "");
                if (!width.isEmpty() && !height.isEmpty()) {
                    videoInfo.append("Video dimensions: ").append(width).append(" x ").append(height).append("\n");
                }

                String duration = parsedDoc.getMetadataOrDefault("duration", "");
                if (!duration.isEmpty()) {
                    videoInfo.append("Duration: ").append(duration);
                }

                if (videoInfo.length() > 0) {
                    builder.setBody(videoInfo.toString());
                    LOG.debug("Set video body from dimensions and duration");
                }
            }
        }
    }

    /**
     * Processes a plain text document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processTextDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata or first line
        if (parsedDoc.getTitle().isEmpty() && !parsedDoc.getBody().isEmpty()) {
            String body = parsedDoc.getBody();
            int endOfFirstLine = body.indexOf('\n');
            if (endOfFirstLine > 0 && endOfFirstLine < 100) {  // Reasonable title length
                String title = body.substring(0, endOfFirstLine).trim();
                builder.setTitle(title);
                LOG.debug("Set text document title from first line: {}", title);
            }
        }
    }

    /**
     * Processes a JSON document, mapping appropriate content to null fields.
     *
     * @param parsedDoc The parsed document.
     * @param builder The builder to update.
     */
    private static void processJsonDocument(ParsedDocument parsedDoc, ParsedDocument.Builder builder) {
        // If title is empty, try to get it from metadata
        if (parsedDoc.getTitle().isEmpty()) {
            // Try to get title from metadata
            String title = parsedDoc.getMetadataOrDefault("title", "");
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("dc:title", "");
            }
            if (title.isEmpty()) {
                title = parsedDoc.getMetadataOrDefault("Title", "");
            }

            // For JSON, we might find a title field in the JSON itself
            if (title.isEmpty() && !parsedDoc.getBody().isEmpty()) {
                String body = parsedDoc.getBody();
                // Simple check for "title" field in JSON
                int titleIndex = body.indexOf("\"title\"");
                if (titleIndex >= 0) {
                    int valueStart = body.indexOf(":", titleIndex);
                    if (valueStart >= 0) {
                        valueStart = body.indexOf("\"", valueStart);
                        if (valueStart >= 0) {
                            int valueEnd = body.indexOf("\"", valueStart + 1);
                            if (valueEnd >= 0) {
                                title = body.substring(valueStart + 1, valueEnd);
                            }
                        }
                    }
                }
            }

            if (!title.isEmpty()) {
                builder.setTitle(title);
                LOG.debug("Set JSON title from metadata or content: {}", title);
            }
        }
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
