# Yappy WikiCrawler Connector Module

## Purpose

The `wikicrawler-connector` module is a Yappy source connector responsible for downloading Wikipedia XML dumps, processing the articles contained within them, and ingesting these articles as `PipeDoc` messages into the Yappy PipeDoc ecosystem.

It consolidates functionality for:
- Downloading Wikipedia dump files with checksum validation.
- Parsing Wikipedia XML (using the Bliki engine) to extract individual articles.
- Transforming extracted `WikiArticle` messages into Yappy `PipeDoc` messages.
- Optionally persisting intermediate `WikiArticle` messages to a Kafka topic.
- Sending final `PipeDoc` messages to the Yappy `ConnectorEngine` service.

## API Endpoint

The primary way to initiate the crawling process is via a REST API endpoint:

- **Endpoint:** `POST /wikicrawler/initiate`
- **Request Body:** JSON object describing the dump to download. See the `InitiateCrawlRequest` schema in the Swagger UI for detailed parameters.
  - `url`: URL of the Wikipedia dump file.
  - `fileName`: Target local file name.
  - `fileDumpDate`: Date of the dump (YYYYMMDD).
  - `errorCheckValue`: Checksum value.
  - `errorCheckType`: Checksum type (MD5, SHA1, SHA256).
  - `expectedFilesInDump`: Optional list of expected files in the dump.

The API documentation (Swagger UI) is typically available at `/swagger-ui` when the application is running.

## Processing Flow

1.  The `/wikicrawler/initiate` endpoint receives a request.
2.  `FileDownloaderService` downloads the specified Wikipedia dump URL, saving it with an `.incomplete` extension, validates its checksum, and then renames it.
3.  `WikiProcessingOrchestrator` coordinates the next steps:
    a.  `BlikiArticleExtractorProcessor` parses the downloaded XML dump file (using the Bliki engine) and extracts individual `WikiArticle` messages.
    b.  (Optional) If `wikicrawler.kafka-produce-articles` is true, each `WikiArticle` is sent to the configured Kafka topic (`kafka.topic.wiki.article`).
    c.  `WikiArticleToPipeDocProcessor` transforms each `WikiArticle` into a `PipeDoc` message.
4.  `YappyIngestionService` sends each `PipeDoc` message to the Yappy `ConnectorEngine` via gRPC.

## Key Configuration Options

Configuration is managed via Micronaut's `application.yml` (or equivalent).

-   **`wikicrawler.base-storage-path`**: (String) File system path where downloaded Wikipedia dumps will be stored (e.g., `data/wiki-dumps`). Default: `downloaded_wikidumps`.
-   **`wikicrawler.kafka-produce-articles`**: (Boolean) Set to `true` to enable sending extracted `WikiArticle` messages to Kafka. Default: `false`.
-   **`kafka.topic.wiki.article`**: (String) The Kafka topic name to produce `WikiArticle` messages to, if enabled. Default: `wiki-articles`.
-   **`grpc.channels.connector-engine-service.address`**: (String) The address (e.g., `host:port`) of the Yappy `ConnectorEngine` gRPC service. Example: `static://localhost:50051`.
-   **`micronaut.application.name`**: (String) Application name, useful for logging and management.
-   **`file.download.base-url`**: (String) Used by the `RawFileDownloaderClient`, primarily for testing with mock servers like WireMock. In production, the full URL is typically provided in the `InitiateCrawlRequest`.

## Bliki Engine Shading

This module uses the Bliki engine for parsing Wikipedia XML. To avoid classpath conflicts, Bliki and its potentially problematic transitive dependencies (like SAX parsers) are shaded into the module under the package `com.krickert.shaded.bliki`.
