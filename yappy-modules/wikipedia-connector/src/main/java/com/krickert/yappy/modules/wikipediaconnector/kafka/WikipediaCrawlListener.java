package com.krickert.yappy.modules.wikipediaconnector.kafka;

import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.wikipedia_crawl_request;
import com.krickert.yappy.modules.wikipediaconnector.config.WikipediaConnectorConfig;
import com.krickert.yappy.modules.wikipediaconnector.service.WikipediaService;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Requires;
import jakarta.inject.Inject;
import org.dkpro.jwpl.api.Wikipedia;
import org.dkpro.jwpl.api.exception.WikiApiException;
import org.dkpro.jwpl.api.exception.WikiInitializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka listener for Wikipedia crawl requests.
 * This listener consumes wikipedia_crawl_request messages from a Kafka topic,
 * retrieves the specified Wikipedia articles, and produces PipeDoc objects with the article data.
 */
@KafkaListener(
        groupId = "${wikipedia.connector.kafka.group-id:wikipedia-connector-group}",
        offsetReset = OffsetReset.EARLIEST,
        clientId = "${wikipedia.connector.kafka.client-id:wikipedia-connector-client}"
)
@Requires(property = "wikipedia.connector.kafka.enabled", value = "true", defaultValue = "true")
public class WikipediaCrawlListener {

    private static final Logger LOG = LoggerFactory.getLogger(WikipediaCrawlListener.class);

    @Inject
    private WikipediaCrawlProducer wikipediaCrawlProducer;

    @Inject
    private WikipediaService wikipediaService;

    /**
     * Processes a Wikipedia crawl request from Kafka.
     * 
     * @param request the Wikipedia crawl request
     */
    @Topic("${wikipedia.connector.kafka.input-topic:wikipedia-crawl-requests}")
    public void receive(wikipedia_crawl_request request) {
        String language = request.getLanguage();
        String title = request.getTitle();
        String category = request.getCategory();
        int maxArticles = request.getMaxArticles() > 0 ? request.getMaxArticles() : 100;
        boolean includeCategories = request.getIncludeCategories();
        boolean includeDiscussions = request.getIncludeDiscussions();
        boolean includeRedirects = request.getIncludeRedirects();

        LOG.info("Received Wikipedia crawl request - language: {}, title: {}, category: {}", 
                language, title, category);

        try {
            // Create a Wikipedia connector configuration
            WikipediaConnectorConfig config = WikipediaConnectorConfig.builder()
                    .language(language)
                    .maxArticles(maxArticles)
                    .includeCategories(includeCategories)
                    .includeDiscussions(includeDiscussions)
                    .includeRedirects(includeRedirects)
                    .build();

            // Create a Wikipedia API instance
            Wikipedia wikipedia = wikipediaService.createWikipediaApi(config);

            // Process the request based on the parameters
            if (title != null && !title.isEmpty()) {
                // Retrieve a specific article by title
                LOG.info("Retrieving Wikipedia article with title: {}", title);
                PipeDoc article = wikipediaService.getArticleByTitle(wikipedia, title);

                // Create a UUID key for the Kafka message
                UUID messageKey = UUID.randomUUID();

                // Send the PipeDoc to the output topic
                wikipediaCrawlProducer.sendDocument(messageKey, article);

                LOG.info("Successfully processed Wikipedia article: {}", title);
            } else if (category != null && !category.isEmpty()) {
                // Retrieve articles by category
                LOG.info("Retrieving Wikipedia articles from category: {}", category);
                List<PipeDoc> articles = wikipediaService.getArticlesByCategory(
                        wikipedia, category, maxArticles, includeCategories);

                LOG.info("Retrieved {} articles from category: {}", articles.size(), category);

                // Send each article to the output topic
                for (PipeDoc article : articles) {
                    // Create a UUID key for the Kafka message
                    UUID messageKey = UUID.randomUUID();

                    // Send the PipeDoc to the output topic
                    wikipediaCrawlProducer.sendDocument(messageKey, article);

                    LOG.debug("Sent article: {}", article.getSourceUri());
                }

                LOG.info("Successfully processed {} Wikipedia articles from category: {}", 
                        articles.size(), category);
            } else {
                LOG.warn("No title or category specified in the request");
            }
        } catch (WikiInitializationException e) {
            LOG.error("Error initializing Wikipedia API: {}", e.getMessage(), e);
        } catch (WikiApiException e) {
            LOG.error("Error retrieving Wikipedia article: {}", e.getMessage(), e);
        } catch (Exception e) {
            LOG.error("Error processing Wikipedia crawl request: {}", e.getMessage(), e);
        }
    }
}
