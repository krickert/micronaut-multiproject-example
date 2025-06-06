package com.krickert.yappy.wikicrawler.service;

import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.WikiArticle;
import com.krickert.yappy.wikicrawler.config.WikiCrawlerConfig;
import com.krickert.yappy.wikicrawler.kafka.DownloadedFileProducer;
import com.krickert.yappy.wikicrawler.kafka.WikiArticleProducer;
import com.krickert.yappy.wikicrawler.processor.BlikiArticleExtractorProcessor;
import com.krickert.yappy.wikicrawler.processor.WikiArticleToPipeDocProcessor;
import com.krickert.search.model.PipeDoc;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Orchestrates the processing of Wikipedia dump files.
 * Each step in the pipeline can optionally use Kafka or internal processing.
 */
@Singleton
public class WikiProcessingOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(WikiProcessingOrchestrator.class);

    private final BlikiArticleExtractorProcessor articleExtractor;
    private final WikiArticleProducer kafkaArticleProducer;
    private final DownloadedFileProducer downloadedFileProducer;
    private final WikiArticleToPipeDocProcessor toPipeDocProcessor;
    private final WikiCrawlerConfig config;

    public WikiProcessingOrchestrator(
            BlikiArticleExtractorProcessor articleExtractor,
            WikiArticleProducer kafkaArticleProducer,
            DownloadedFileProducer downloadedFileProducer,
            WikiArticleToPipeDocProcessor toPipeDocProcessor,
            WikiCrawlerConfig config) {
        this.articleExtractor = articleExtractor;
        this.kafkaArticleProducer = kafkaArticleProducer;
        this.downloadedFileProducer = downloadedFileProducer;
        this.toPipeDocProcessor = toPipeDocProcessor;
        this.config = config;
    }

    /**
     * Processes a downloaded Wikipedia dump file.
     * If configured, the downloaded file is sent to Kafka.
     * Articles are extracted, optionally sent to Kafka, and then transformed into PipeDocs.
     *
     * @param downloadedFile The file to process.
     * @return A Flux of PipeDoc messages.
     */
    public Flux<PipeDoc> processDump(DownloadedFile downloadedFile) {
        LOG.info("Starting processing for dump: {}", downloadedFile.getFileName());

        // Optionally send the downloaded file to Kafka
        if (config.isKafkaProduceDownloadedFiles()) {
            LOG.debug("Sending DownloadedFile {} to Kafka topic {}", 
                    downloadedFile.getFileName(), config.getDownloadedFileTopic());
            downloadedFileProducer.sendDownloadedFile(downloadedFile.getFileName(), downloadedFile);
        }

        return Flux.create(emitter -> {
            Consumer<WikiArticle> articleConsumer = article -> {
                try {
                    if (config.isKafkaProduceArticles()) {
                        LOG.debug("Sending WikiArticle ID {} to Kafka topic {}", 
                                article.getId(), config.getArticleOutputTopic());
                        kafkaArticleProducer.sendWikiArticle(article.getId(), article);
                    }
                    // Regardless of Kafka, transform to PipeDoc for further in-process flow
                    PipeDoc pipeDoc = toPipeDocProcessor.transform(article);
                    emitter.next(pipeDoc);
                } catch (Exception e) {
                    LOG.error("Error processing article ID {} or sending to Kafka/transforming: ", 
                            article.getId(), e);
                    emitter.error(e); // Propagate error to the Flux stream
                }
            };

            try {
                // Run blocking Bliki parsing on a different thread (implicitly by Flux.create or explicitly)
                // For true non-blocking, this should be explicitly scheduled if Bliki is heavily blocking.
                // Flux.create itself doesn't make the enclosed code non-blocking but provides a bridge.
                articleExtractor.parseWikiDump(downloadedFile, articleConsumer);
                emitter.complete();
            } catch (IOException e) {
                LOG.error("Failed to parse wiki dump {}: ", downloadedFile.getFileName(), e);
                emitter.error(e);
            }
        });
    }
}
