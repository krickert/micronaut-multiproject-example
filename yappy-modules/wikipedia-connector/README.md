# Wikipedia Connector

A connector for crawling Wikipedia articles and integrating them into the YAPPY pipeline.

## Overview

The Wikipedia Connector is a module for the YAPPY platform that allows crawling and processing Wikipedia articles. It uses the [DKPro JWPL](https://dkpro.github.io/dkpro-jwpl/) library to access Wikipedia content and streams the articles into Kafka for further processing.

## Features

- Connect to Wikipedia dumps or databases
- Filter articles by title, category, or other criteria
- Stream articles to Kafka for processing
- Configure via JSON schema
- gRPC service integration

## Configuration

The connector can be configured with the following options:

```json
{
  "host": "localhost",
  "port": 3306,
  "database": "wikipedia",
  "user": "wikiuser",
  "password": "wikipass",
  "dumpPath": "/path/to/dumps",
  "language": "en",
  "categoryFilter": "Science",
  "titleFilter": "Physics",
  "maxArticles": 100,
  "includeCategories": false,
  "includeDiscussions": false,
  "includeRedirects": false,
  "kafkaTopic": "wikipedia-articles",
  "logPrefix": "[WIKI] "
}
```

## Usage

### As a gRPC Service

The connector can be used as a gRPC service in a YAPPY pipeline:

```yaml
pipelines:
  - name: wikipedia-processing
    steps:
      - name: wikipedia-connector
        service: wikipedia-connector
        config:
          language: "en"
          database: "wikipedia"
          maxArticles: 100
      - name: tika-parser
        service: tika-parser
      - name: embedder
        service: embedder
```

### Via Kafka

You can also send crawl requests via Kafka:

```java
// Create a Wikipedia crawl request
wikipedia_crawl_request request = wikipedia_crawl_request.newBuilder()
    .setLanguage("en")
    .setTitle("Artificial Intelligence")
    .setMaxArticles(10)
    .build();

// Send the request to the input topic
kafkaTemplate.send("wikipedia-crawl-requests", request);
```

## Dependencies

- DKPro JWPL 2.0.0
- Micronaut Framework
- Kafka
- gRPC

## Development

### Building

```bash
./gradlew :yappy-modules:wikipedia-connector:build
```

### Testing

```bash
./gradlew :yappy-modules:wikipedia-connector:test
```

## Implementation Details

The Wikipedia Connector uses the [DKPro JWPL](https://dkpro.github.io/dkpro-jwpl/) library to access Wikipedia content. The implementation includes:

### Core Components

1. **WikipediaService**: Handles the connection to Wikipedia and provides methods to retrieve articles.
   - `createWikipediaApi(config)`: Creates a Wikipedia API instance based on the provided configuration.
   - `getArticleByTitle(wikipedia, title)`: Retrieves a Wikipedia article by title.
   - `getArticlesByCategory(wikipedia, categoryName, maxArticles, includeSubcategories)`: Retrieves Wikipedia articles by category.

2. **WikipediaConnectorService**: Implements the PipeStepProcessor interface for use in YAPPY pipelines.
   - Processes requests to connect to a Wikipedia database and retrieve articles.
   - Supports retrieving articles by title or category.

3. **WikipediaCrawlListener**: Listens for Wikipedia crawl requests from Kafka.
   - Processes requests to retrieve Wikipedia articles and sends them to the output topic.
   - Supports retrieving articles by title or category.

### Database Setup

To use the Wikipedia Connector, you need to set up a Wikipedia database:

1. Download a Wikipedia dump from [Wikimedia Downloads](https://dumps.wikimedia.org/).
2. Use the DKPro JWPL DataMachine to import the dump into a MySQL database.
3. Configure the connector to connect to the database.

Example DataMachine command:
```bash
java -jar dkpro-jwpl-datamachine-2.0.0.jar -e wikipedia -l en -f path/to/dump -d wikipedia -h localhost -u wikiuser -p wikipass
```

## Future Enhancements

- Add support for incremental crawling
- Add more filtering options (by date, by namespace, etc.)
- Implement caching for better performance
- Add support for Wikipedia API (in addition to database access)
