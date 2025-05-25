package com.krickert.yappy.wikicrawler.processor;

import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.WikiArticle;
import com.krickert.search.model.wiki.WikiSiteInfo;
import com.krickert.search.model.wiki.WikiType;
import com.google.protobuf.Timestamp;
import info.bliki.wiki.dump.IArticleFilter;
import info.bliki.wiki.dump.Siteinfo;
import info.bliki.wiki.dump.WikiXMLParser;
import info.bliki.wiki.model.WikiModel;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import Bliki engine classes. Note: these are using the shaded path.
// Actual Bliki usage might require specific model and parser classes from the library.
// Example: import com.krickert.shaded.bliki.wiki.dump.IArticleFilter;
// Example: import com.krickert.shaded.bliki.wiki.dump.WikiArticle parochialArticle; // Bliki's own article model
// Example: import com.krickert.shaded.bliki.wiki.dump.Siteinfo;
// Example: import com.krickert.shaded.bliki.wiki.dump.WikiXMLParser;
// Example: import com.krickert.shaded.bliki.wiki.model.WikiModel;



import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.Locale;


@Singleton
public class BlikiArticleExtractorProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BlikiArticleExtractorProcessor.class);
    private final WikiModel wikiModel; // For rendering wikitext to plain text

    public BlikiArticleExtractorProcessor() {
        // Initialize WikiModel for converting wikitext to plain text.
        // The nulls are for image and link URL prefixes, adjust if needed.
        this.wikiModel = new WikiModel(null, null);
        // It might be necessary to configure the Namespace if Bliki doesn't default correctly
        // For English Wikipedia:
        // Namespace.initialize(((org.xml.sax.Attributes) new Siteinfo()).getNamespaces());
    }

    public void parseWikiDump(DownloadedFile downloadedFile, Consumer<WikiArticle> articleConsumer) throws IOException {
        if (downloadedFile.getAccessUrisList().isEmpty()) {
            LOG.warn("No access URIs found for file: {}", downloadedFile.getFileName());
            return;
        }

        // Assuming the first URI is a local file path
        String accessUri = downloadedFile.getAccessUris(0);
        if (!accessUri.startsWith("file:")) {
            LOG.error("Unsupported URI scheme for Bliki parsing: {}. Only 'file:' is supported.", accessUri);
            throw new IOException("Unsupported URI scheme: " + accessUri);
        }

        Path filePath = Paths.get(java.net.URI.create(accessUri));
        LOG.info("Starting Bliki parsing for dump file: {}", filePath);

        try (FileInputStream fis = new FileInputStream(filePath.toFile())) {
            // Using bzip2 decompression requires an external library or a Bliki version that handles it.
            // For now, assuming uncompressed XML or that Bliki's WikiXMLParser can handle .bz2 if configured with appropriate stream.
            // If using Apache Commons Compress for BZip2:
            // org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream bzIn = 
            //     new org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream(fis);

            WikiXMLParser parser = new WikiXMLParser(fis, new ArticleFilter(articleConsumer, wikiModel, downloadedFile.getFileDumpDate()));
            parser.parse(); // Starts the parsing process

        } catch (Exception e) { // Bliki parser can throw various exceptions
            LOG.error("Error parsing Wikipedia dump file {}: ", filePath, e);
            throw new IOException("Failed to parse Wikipedia dump: " + filePath, e);
        }
        LOG.info("Finished Bliki parsing for dump file: {}", filePath);
    }

    private static class ArticleFilter implements IArticleFilter {
        private final Consumer<WikiArticle> consumer;
        private final WikiModel wikiModel;
        private final String dumpTimestampStr; // Timestamp of the dump generation

        public ArticleFilter(Consumer<WikiArticle> consumer, WikiModel wikiModel, String dumpTimestampStr) {
            this.consumer = consumer;
            this.wikiModel = wikiModel;
            this.dumpTimestampStr = dumpTimestampStr;
        }

        @Override
        public void process(info.bliki.wiki.dump.WikiArticle page, Siteinfo siteinfo) {
            if (page.isMain() || page.isCategory()) { // Process articles and categories, extend if needed
                wikiModel.setUp(); // Important: Reset model for each page
                String plainText = wikiModel.render(page.getText());
                wikiModel.tearDown();

                Instant parsedAt = Instant.now();
                
                // Attempt to parse article timestamp
                Timestamp articleRevisionTimestamp = parseTimestamp(page.getTimeStamp());


                WikiArticle.Builder articleBuilder = WikiArticle.newBuilder()
                        .setId(page.getId())
                        .setTitle(page.getTitle())
                        .setText(plainText)
                        .setWikiText(page.getText() == null ? "" : page.getText())
                        .setNamespaceCode(page.getIntegerNamespace())
                        .setNamespace(page.getNamespace())
                        .setDumpTimestamp(dumpTimestampStr) // From the dump file generation
                        .setRevisionId(page.getRevisionId())
                        .setArticleVersion(page.getRevisionId()) // Using revisionId as article_version
                        .setDateParsed(Timestamp.newBuilder().setSeconds(parsedAt.getEpochSecond()).setNanos(parsedAt.getNano()).build());
                
                if (articleRevisionTimestamp != null) {
                     articleBuilder.setTimestamp(articleRevisionTimestamp);
                }


                WikiSiteInfo.Builder siteInfoBuilder = WikiSiteInfo.newBuilder();
                if (siteinfo != null) {
                    siteInfoBuilder.setSiteName(siteinfo.getSitename());
                    siteInfoBuilder.setBase(siteinfo.getBase());
                    siteInfoBuilder.setGenerator(siteinfo.getGenerator());
                    siteInfoBuilder.setCharacterCase(siteinfo.getCase());
                }
                articleBuilder.setSiteInfo(siteInfoBuilder.build());

                // Determine WikiType (basic example)
                if (page.isCategory()) {
                    articleBuilder.setWikiType(WikiType.CATEGORY);
                } else if (page.isTemplate()) {
                    articleBuilder.setWikiType(WikiType.TEMPLATE);
                } else if (page.isRedirect()) {
                    articleBuilder.setWikiType(WikiType.REDIRECT);
                } else if (page.isFile()) {
                    articleBuilder.setWikiType(WikiType.FILE);
                } else {
                    articleBuilder.setWikiType(WikiType.ARTICLE); // Default
                }
                // Add more logic for other WikiTypes (LIST, DRAFT, WIKIPEDIA) if identifiable

                consumer.accept(articleBuilder.build());
            }
        }
        
        private Timestamp parseTimestamp(String blikiTimestamp) {
            if (blikiTimestamp == null || blikiTimestamp.isEmpty()) {
                return null;
            }
            try {
                // Bliki timestamps are typically like "2023-10-01T10:20:30Z"
                Instant instant = Instant.parse(blikiTimestamp);
                return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
            } catch (Exception e) {
                LOG.warn("Could not parse article timestamp from Bliki: {}", blikiTimestamp, e);
                return null;
            }
        }
    }
}
