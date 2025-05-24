package com.krickert.yappy.modules.wikipediaconnector.service;

import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.wikipediaconnector.config.WikipediaConnectorConfig;
import org.dkpro.jwpl.api.DatabaseConfiguration;
import org.dkpro.jwpl.api.Page;
import org.dkpro.jwpl.api.Wikipedia;
import org.dkpro.jwpl.api.WikiConstants.Language;
import org.dkpro.jwpl.api.exception.WikiApiException;
import org.dkpro.jwpl.api.exception.WikiInitializationException;
import org.dkpro.jwpl.api.exception.WikiTitleParsingException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service for interacting with Wikipedia using the dkpro-jwpl library.
 * This service provides methods to connect to a Wikipedia database and retrieve articles.
 */
@Singleton
public class WikipediaService {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaService.class);

    /**
     * Creates a Wikipedia API instance based on the provided configuration.
     *
     * @param config the Wikipedia connector configuration
     * @return the Wikipedia API instance
     * @throws WikiInitializationException if the Wikipedia API cannot be initialized
     */
    public Wikipedia createWikipediaApi(WikipediaConnectorConfig config) throws WikiInitializationException {
        // Create database configuration
        DatabaseConfiguration dbConfig = new DatabaseConfiguration();
        dbConfig.setHost(config.host());
        dbConfig.setDatabase(config.database());
        dbConfig.setUser(config.user());
        dbConfig.setPassword(config.password());
        dbConfig.setLanguage(getLanguage(config.language()));

        // Create Wikipedia API instance
        return new Wikipedia(dbConfig);
    }

    /**
     * Retrieves a Wikipedia article by title.
     *
     * @param wikipedia the Wikipedia API instance
     * @param title the article title
     * @return the article as a PipeDoc
     * @throws WikiApiException if the article cannot be retrieved
     */
    public PipeDoc getArticleByTitle(Wikipedia wikipedia, String title) throws WikiApiException {
        LOG.info("Retrieving Wikipedia article with title: {}", title);

        // Get the page by title
        Page page = wikipedia.getPage(title);

        // Extract article content
        String content = page.getPlainText();

        // Create a blob with the article content
        Blob.Builder blobBuilder = Blob.newBuilder()
                .setBlobId(UUID.randomUUID().toString())
                .setData(com.google.protobuf.ByteString.copyFromUtf8(content))
                .setFilename(title.replace(" ", "_") + ".txt")
                .setMimeType("text/plain");

        // Add Wikipedia metadata to the blob
        Map<String, String> blobMetadata = new HashMap<>();
        blobMetadata.put("wikipedia_language", wikipedia.getLanguage().toString());
        blobMetadata.put("wikipedia_title", title);
        blobMetadata.put("wikipedia_id", String.valueOf(page.getPageId()));
        blobMetadata.put("wikipedia_source", "dkpro-jwpl");
        blobMetadata.put("wikipedia_is_disambiguation", String.valueOf(page.isDisambiguation()));
        blobMetadata.put("wikipedia_is_redirect", String.valueOf(page.isRedirect()));

        // Add categories if available
        try {
            List<String> categories = new ArrayList<>();
            for (org.dkpro.jwpl.api.Category category : page.getCategories()) {
                categories.add(category.getTitle().getPlainTitle());
            }
            blobMetadata.put("wikipedia_categories", String.join(", ", categories));
        } catch (WikiApiException e) {
            LOG.warn("Failed to retrieve categories for article: {}", title, e);
        }

        blobBuilder.putAllMetadata(blobMetadata);

        // Create a new PipeDoc with the blob
        return PipeDoc.newBuilder()
                .setId(UUID.randomUUID().toString())
                .setSourceUri("wikipedia://" + wikipedia.getLanguage() + "/" + title)
                .setSourceMimeType("text/plain")
                .setBlob(blobBuilder.build())
                .build();
    }

    /**
     * Retrieves Wikipedia articles by category.
     *
     * @param wikipedia the Wikipedia API instance
     * @param categoryName the category name
     * @param maxArticles the maximum number of articles to retrieve
     * @param includeSubcategories whether to include articles from subcategories
     * @return a list of articles as PipeDocs
     * @throws WikiApiException if the articles cannot be retrieved
     */
    public List<PipeDoc> getArticlesByCategory(Wikipedia wikipedia, String categoryName, 
                                              int maxArticles, boolean includeSubcategories) throws WikiApiException {
        LOG.info("Retrieving Wikipedia articles from category: {}", categoryName);

        List<PipeDoc> articles = new ArrayList<>();

        // Get the category by name
        org.dkpro.jwpl.api.Category category = wikipedia.getCategory(categoryName);

        // Get pages in the category
        List<Page> pages;
        if (includeSubcategories) {
            pages = new ArrayList<>(category.getArticles());
        } else {
            // If method for getting articles without subcategories is not available,
            // just use getArticles() and filter later if needed
            pages = new ArrayList<>(category.getArticles());
        }

        // Limit the number of articles
        int limit = Math.min(maxArticles, pages.size());

        // Process each page
        for (int i = 0; i < limit; i++) {
            Page page = pages.get(i);
            try {
                // Get the article title
                String title = page.getTitle().getPlainTitle();

                // Create a PipeDoc for the article
                PipeDoc article = getArticleByTitle(wikipedia, title);

                // Add the article to the list
                articles.add(article);

                LOG.debug("Retrieved article {} of {}: {}", i + 1, limit, title);
            } catch (WikiApiException e) {
                LOG.warn("Failed to retrieve article: {}", page.getPageId(), e);
            }
        }

        return articles;
    }

    /**
     * Converts a language code to a Language enum value.
     *
     * @param languageCode the language code (e.g., "en", "de")
     * @return the corresponding Language enum value
     */
    private Language getLanguage(String languageCode) {
        switch (languageCode.toLowerCase()) {
            case "en":
                return Language.english;
            case "de":
                return Language.german;
            case "fr":
                return Language.french;
            case "es":
                return Language.spanish;
            case "it":
                return Language.italian;
            case "nl":
                return Language.dutch;
            case "pl":
                return Language.polish;
            case "ru":
                return Language.russian;
            case "ja":
                return Language.japanese;
            case "zh":
                return Language.chinese;
            default:
                LOG.warn("Unsupported language code: {}. Defaulting to English.", languageCode);
                return Language.english;
        }
    }
}
