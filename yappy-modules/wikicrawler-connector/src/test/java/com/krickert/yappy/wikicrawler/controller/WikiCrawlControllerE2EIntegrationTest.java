package com.krickert.yappy.wikicrawler.controller;

import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.connector.test.server.ConnectorTestHelper;
import com.krickert.yappy.wikicrawler.component.MockFileDownloader;
import com.krickert.yappy.wikicrawler.connector.YappyIngestionService;
import com.krickert.yappy.wikicrawler.controller.model.InitiateCrawlRequest;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class WikiCrawlControllerE2EIntegrationTest {
    private static final Logger LOG = LoggerFactory.getLogger(WikiCrawlControllerE2EIntegrationTest.class);

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    YappyIngestionService yappyIngestionService; // Using the real YappyIngestionService that connects to our test server

    @Inject
    MockFileDownloader mockFileDownloader;

    @TempDir
    Path tempDir;

    private String sampleXmlContent;
    private String expectedMd5Checksum;
    private static final String TEST_FILE_NAME = "e2e-test-dump.xml";
    private static final String TEST_DUMP_DATE = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final Path CONFIGURED_BASE_STORAGE_PATH = Path.of("build/tmp/wikicrawler-test-downloads");

    @BeforeEach
    void setUp() throws Exception {
        // Load sample XML content from resources
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("sample-wiki-dump.xml")) {
            assertNotNull(is, "sample-wiki-dump.xml not found in test resources");
            sampleXmlContent = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        // Calculate MD5 checksum for the sample XML content
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(sampleXmlContent.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            hexString.append(String.format("%02x", b));
        }
        expectedMd5Checksum = hexString.toString();

        // Clean up download storage before test
        deleteDirectoryRecursively(CONFIGURED_BASE_STORAGE_PATH);
        Files.createDirectories(CONFIGURED_BASE_STORAGE_PATH);

        // Create a sample XML file in the temp directory that will be used by the MockFileDownloader
        Path sampleXmlFile = tempDir.resolve(TEST_FILE_NAME);
        Files.writeString(sampleXmlFile, sampleXmlContent);
        LOG.info("Created sample XML file at: {}", sampleXmlFile);
        LOG.info("Test setup complete. MD5 checksum: {}", expectedMd5Checksum);
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         LOG.error("Error deleting file: {}", p, e);
                     }
                 });
        }
    }

    @AfterEach
    void tearDown() {
        try {
            deleteDirectoryRecursively(CONFIGURED_BASE_STORAGE_PATH.resolve(TEST_DUMP_DATE));
        } catch (IOException e) {
            LOG.error("Error cleaning up test directory", e);
        }
    }

    @Test
    void testInitiateCrawl_Success() {
        // Create a test URL that will be handled by the MockFileDownloader
        String testUrl = "http://test.example.com/" + TEST_FILE_NAME;

        InitiateCrawlRequest crawlRequest = new InitiateCrawlRequest();
        crawlRequest.setUrl(testUrl);
        crawlRequest.setFileName(TEST_FILE_NAME);
        crawlRequest.setFileDumpDate(TEST_DUMP_DATE);
        crawlRequest.setErrorCheckType("MD5");
        crawlRequest.setErrorCheckValue(expectedMd5Checksum);
        crawlRequest.setExpectedFilesInDump(Collections.emptyList());

        HttpRequest<InitiateCrawlRequest> request = HttpRequest.POST("/wikicrawler/initiate", crawlRequest);
        HttpResponse<CrawlResponse> response = httpClient.toBlocking().exchange(request, CrawlResponse.class);

        assertEquals(HttpStatus.OK, response.getStatus());
        CrawlResponse crawlResponse = response.body();
        assertNotNull(crawlResponse);
        assertTrue(crawlResponse.success);
        // sample-wiki-dump.xml has 2 processable articles (1 main, 1 category)
        assertEquals(2, crawlResponse.documentsIngested, "Should report 2 documents ingested");
        assertFalse(crawlResponse.streamIds.isEmpty(), "Stream IDs should be present.");
    }

    @Test
    void testInitiateCrawl_DownloadFails_ChecksumMismatch() {
        // Create a test URL that will be handled by the MockFileDownloader
        String testUrl = "http://test.example.com/" + TEST_FILE_NAME;

        InitiateCrawlRequest crawlRequest = new InitiateCrawlRequest();
        crawlRequest.setUrl(testUrl);
        crawlRequest.setFileName(TEST_FILE_NAME);
        crawlRequest.setFileDumpDate(TEST_DUMP_DATE);
        crawlRequest.setErrorCheckType("MD5");
        crawlRequest.setErrorCheckValue("incorrectChecksumValue123"); // Deliberate mismatch
        crawlRequest.setExpectedFilesInDump(Collections.emptyList());

        HttpRequest<InitiateCrawlRequest> request = HttpRequest.POST("/wikicrawler/initiate", crawlRequest);

        try {
            httpClient.toBlocking().exchange(request, CrawlResponse.class);
            fail("Expected HttpClientResponseException due to server error from checksum failure.");
        } catch (HttpClientResponseException e) {
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, e.getStatus());
            CrawlResponse crawlResponse = e.getResponse().getBody(CrawlResponse.class).orElse(null);
            assertNotNull(crawlResponse);
            assertFalse(crawlResponse.success);
            assertTrue(crawlResponse.message.contains("Checksum validation failed") || crawlResponse.message.contains("Failed to process crawl"), "Error message mismatch: " + crawlResponse.message);
        }
    }
}
