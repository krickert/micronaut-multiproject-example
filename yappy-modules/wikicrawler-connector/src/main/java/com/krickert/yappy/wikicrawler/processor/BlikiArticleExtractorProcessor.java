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
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// Import Bliki engine classes. Note: these are using the shaded path.
// Actual Bliki usage might require specific model and parser classes from the library.
// Example: import com.krickert.shaded.bliki.wiki.dump.IArticleFilter;
// Example: import com.krickert.shaded.bliki.wiki.dump.WikiArticle parochialArticle; // Bliki's own article model
// Example: import com.krickert.shaded.bliki.wiki.dump.Siteinfo;
// Example: import com.krickert.shaded.bliki.wiki.dump.WikiXMLParser;
// Example: import com.krickert.shaded.bliki.wiki.model.WikiModel;

import info.bliki.wiki.dump.IArticleFilter;
import info.bliki.wiki.dump.Siteinfo;
import info.bliki.wiki.dump.WikiXMLParser;
import info.bliki.wiki.model.WikiModel;
import info.bliki.wiki.namespaces.Namespace;


import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.Locale;

import static com.google.common.base.Preconditions.checkNotNull;


@Singleton
public class BlikiArticleExtractorProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(BlikiArticleExtractorProcessor.class);
    private final WikiModel wikiModel; // For rendering wikitext to plain text
    private static final WikiURLExtractor urlExtractor = new WikiURLExtractor();
    private final WikiMarkupCleaner wikiClean;
    public BlikiArticleExtractorProcessor(WikiMarkupCleaner wikiClean) {
        // Initialize WikiModel for converting wikitext to plain text.
        // The nulls are for image and link URL prefixes, adjust if needed.
        this.wikiModel = new WikiModel(null, null);
        this.wikiClean = checkNotNull(wikiClean);
        // It might be necessary to configure the Namespace if Bliki doesn't default correctly
        // For English Wikipedia:
        //Namespace.initialize(((org.xml.sax.Attributes) new Siteinfo()).getNamespaces());
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
        public void process(info.bliki.wiki.dump.WikiArticle page, Siteinfo siteinfo) throws IOException {
            if (page.isMain() || page.isCategory()) { // Process articles and categories, extend if needed
                wikiModel.setUp(); // Important: Reset model for each page
                String plainText = wikiModel.render(page.getText());
                wikiModel.tearDown();
                //TODO: debug this and see how this turned out...
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
                        .addAllUrlReferences(urlExtractor.parseUrlEntries(page.getText()))
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
                    // Based on user's old code, try getCharacterCase().
                    // If this method doesn't exist in Bliki 3.1.0, it will fail compilation.
                    // The original error was for getCase().
                    String siteCase = siteinfo.getCharacterCase();
                    if (siteCase != null) {
                         siteInfoBuilder.setCharacterCase(siteCase);
                    }
                }
                articleBuilder.setSiteInfo(siteInfoBuilder.build());

                String title = articleBuilder.getTitle();
                String wikiBody = articleBuilder.getWikiText();
                // Determine WikiType
                if (title.contains("REDIRECT")
                        || (StringUtils.isNotEmpty(wikiBody) &&
                        (wikiBody.startsWith("#REDIRECT")
                                || wikiBody.startsWith("#redirect")))) {
                    articleBuilder.setWikiType(WikiType.REDIRECT);
                } else if (page.isCategory()) {
                    articleBuilder.setWikiType(WikiType.CATEGORY);
                } else if (page.isTemplate()) {
                    articleBuilder.setWikiType(WikiType.TEMPLATE);
                } else if (page.isFile()) {
                    articleBuilder.setWikiType(WikiType.FILE);
                } else if (page.isMain()) { // isMain() for regular articles
                    articleBuilder.setWikiType(WikiType.ARTICLE);
                } else {
                    LOG.debug("Skipping page '{}' (ID: {}) of namespace {} as its type is not explicitly handled.", page.getTitle(), page.getId(), page.getIntegerNamespace());
                    return; // Don't process this page further
                }
                consumer.accept(articleBuilder.build()); // All processed types reach here
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

    private WikiType findWikiCategory(String title, String wikiBody) {
        if (title.contains("REDIRECT")
                || (StringUtils.isNotEmpty(wikiBody) &&
                (wikiBody.startsWith("#REDIRECT")
                        || wikiBody.startsWith("#redirect")))) {
            return WikiType.REDIRECT;
        } else if (title.startsWith("Category:")) {
            return WikiType.CATEGORY;
        } else if (title.startsWith("List of")) {
            return WikiType.LIST;
        } else if (title.startsWith("Wikipedia:")) {
            return WikiType.WIKIPEDIA;
        } else if (title.startsWith("Draft:")) {
            return WikiType.DRAFT;
        } else if (title.startsWith("Template:")) {
            return WikiType.TEMPLATE;
        } else if (title.startsWith("File:")) {
            return WikiType.FILE;
        } else {
            return WikiType.ARTICLE;
        }
    }
}
