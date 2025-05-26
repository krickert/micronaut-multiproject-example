package com.krickert.yappy.wikicrawler.controller;

import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.wiki.DownloadFileRequest;
import com.krickert.search.model.wiki.ErrorCheck;
import com.krickert.search.model.wiki.ErrorCheckType;
import com.krickert.yappy.wikicrawler.config.WikiCrawlerConfig;
import com.krickert.yappy.wikicrawler.controller.model.InitiateCrawlRequest;
import com.krickert.yappy.wikicrawler.kafka.DownloadRequestProducer;
import com.krickert.yappy.wikicrawler.service.FileDownloaderService;
import com.krickert.yappy.wikicrawler.service.WikiProcessingOrchestrator;
import com.krickert.yappy.wikicrawler.connector.YappyIngestionService;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.scheduling.TaskExecutors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Controller for managing Wikipedia dump crawling and ingestion.
 * Each step in the pipeline can optionally use Kafka or internal processing.
 */
@Controller("/wikicrawler") // Base path for this controller
@Tag(name = "Wikipedia Crawler", description = "Endpoints for managing Wikipedia dump crawling and ingestion.")
public class WikiCrawlController {

    private static final Logger LOG = LoggerFactory.getLogger(WikiCrawlController.class);

    private final FileDownloaderService fileDownloaderService;
    private final WikiProcessingOrchestrator processingOrchestrator;
    private final YappyIngestionService yappyIngestionService;
    private final DownloadRequestProducer downloadRequestProducer;
    private final WikiCrawlerConfig config;

    public WikiCrawlController(
            FileDownloaderService fileDownloaderService,
            WikiProcessingOrchestrator processingOrchestrator,
            YappyIngestionService yappyIngestionService,
            DownloadRequestProducer downloadRequestProducer,
            WikiCrawlerConfig config) {
        this.fileDownloaderService = fileDownloaderService;
        this.processingOrchestrator = processingOrchestrator;
        this.yappyIngestionService = yappyIngestionService;
        this.downloadRequestProducer = downloadRequestProducer;
        this.config = config;
    }

    @Post(uri = "/initiate", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
    @ExecuteOn(TaskExecutors.IO) // Offload controller logic to I/O thread pool
    @Operation(summary = "Initiate Wikipedia Dump Crawl",
               description = "Downloads a Wikipedia XML dump file, processes its articles, and ingests them into the Yappy system.")
    @ApiResponse(responseCode = "200", description = "Crawl process initiated and completed (or an error occurred during processing). Check 'documentsIngested' and 'message' for details.",
                 content = @Content(schema = @Schema(implementation = CrawlResponse.class)))
    @ApiResponse(responseCode = "400", description = "Bad Request - Invalid input parameters, e.g., invalid errorCheckType.",
                 content = @Content(schema = @Schema(implementation = CrawlResponse.class)))
    @ApiResponse(responseCode = "500", description = "Server Error - An unexpected error occurred during processing.",
                 content = @Content(schema = @Schema(implementation = CrawlResponse.class)))
    public Mono<MutableHttpResponse<CrawlResponse>> initiateCrawl(
            @RequestBody(description = "Details for the Wikipedia dump to crawl.", required = true,
                         content = @Content(schema = @Schema(implementation = InitiateCrawlRequest.class)))
            @Body InitiateCrawlRequest request) {
        LOG.info("Received crawl initiation request for URL: {}", request.getUrl());

        ErrorCheckType ecType;
        try {
            ecType = ErrorCheckType.valueOf(request.getErrorCheckType().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.error("Invalid error check type: {}", request.getErrorCheckType(), e);
            return Mono.just(HttpResponse.<CrawlResponse>badRequest(
                    new CrawlResponse(false, "Invalid errorCheckType: " + request.getErrorCheckType(), 0, null)));
        }

        DownloadFileRequest downloadRequest = DownloadFileRequest.newBuilder()
                .setUrl(request.getUrl())
                .setFileName(request.getFileName())
                .setFileDumpDate(request.getFileDumpDate())
                .setErrorCheck(ErrorCheck.newBuilder()
                        .setErrorCheckType(ecType)
                        .setErrorCheck(request.getErrorCheckValue())
                        .build())
                .addAllExpectedFilesInDump(request.getExpectedFilesInDump() == null ? 
                        new ArrayList<>() : request.getExpectedFilesInDump())
                .build();

        // If configured to use Kafka for download requests, send the request to Kafka
        // and return a response immediately
        if (config.isKafkaProduceDownloadRequests()) {
            UUID requestId = UUID.randomUUID();
            LOG.debug("Sending download request to Kafka topic {}", config.getDownloadRequestTopic());
            downloadRequestProducer.sendDownloadRequest(requestId, downloadRequest);
            return Mono.just(HttpResponse.ok(new CrawlResponse(true, 
                    "Download request sent to Kafka. Request ID: " + requestId, 0, null)));
        }

        // Otherwise, process the request synchronously
        return fileDownloaderService.downloadFile(downloadRequest)
                .flatMapMany(processingOrchestrator::processDump)
                .flatMap(yappyIngestionService::ingestPipeDoc)
                .collectList() // Collect all ConnectorResponse objects
                .map(responses -> {
                    long successfulIngestions = responses.stream()
                            .filter(r -> r != null && r.getAccepted()).count();
                    List<String> streamIds = responses.stream()
                            .filter(r -> r != null && r.getAccepted())
                            .map(ConnectorResponse::getStreamId)
                            .collect(Collectors.toList());
                    LOG.info("Crawl for {} completed. {} documents ingested. Stream IDs: {}", 
                            request.getUrl(), successfulIngestions, streamIds);
                    return HttpResponse.ok(new CrawlResponse(true, 
                            "Crawl processed. " + successfulIngestions + " documents ingested.", 
                            successfulIngestions, streamIds));
                })
                .onErrorResume(e -> {
                    LOG.error("Error during crawl processing for URL {}: ", request.getUrl(), e);
                    return Mono.just(HttpResponse.<CrawlResponse>serverError(
                            new CrawlResponse(false, "Failed to process crawl: " + e.getMessage(), 0, null)));
                });
    }
}
