package com.krickert.yappy.wikicrawler.controller;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.krickert.search.engine.ConnectorResponse;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.wikicrawler.connector.YappyIngestionService;
import com.krickert.yappy.wikicrawler.controller.model.InitiateCrawlRequest;
// Ensure this is the correct import if needed by InitiateCrawlRequest - it is not, ErrorCheckType is used internally by controller
// import com.krickert.search.model.wiki.ErrorCheckType; 
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@MicronautTest
class WikiCrawlControllerE2EIntegrationTest {

    @Inject
    @Client("/")
    HttpClient httpClient;

    @Inject
    YappyIngestionService mockYappyIngestionService; // Micronaut will inject the mock bean

    private WireMockServer wireMockServer;
    private String sampleXmlContent;
    private String expectedMd5Checksum;
    private static final String TEST_FILE_NAME = "e2e-test-dump.xml";
    private static final String TEST_DUMP_DATE = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private static final Path CONFIGURED_BASE_STORAGE_PATH = Path.of("build/tmp/wikicrawler-test-downloads");


    // Define a MockBean for YappyIngestionService
    @MockBean(YappyIngestionService.class)
    YappyIngestionService yappyIngestionService() {
        return mock(YappyIngestionService.class);
    }

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

        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        System.setProperty("wiremock.server.port", String.valueOf(wireMockServer.port())); // For application-test.yml

        stubFor(get(urlEqualTo("/" + TEST_FILE_NAME)) // Relative path for the client
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/xml")
                        .withBody(sampleXmlContent)));

        // Configure mock YappyIngestionService
        ConnectorResponse mockConnectorResponse = ConnectorResponse.newBuilder()
                .setAccepted(true)
                .setStreamId("test-stream-id-123")
                .setMessage("Successfully ingested by mock.")
                .build();
        // Use lenient().when() if the number of calls is variable or might be zero in some paths
        lenient().when(mockYappyIngestionService.ingestPipeDoc(any(PipeDoc.class)))
               .thenReturn(Mono.just(mockConnectorResponse));
    }
    
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         // log
                     }
                 });
        }
    }

    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        reset(mockYappyIngestionService); // Reset Mockito mock after each test
    }

    @Test
    void testInitiateCrawl_Success() {
        InitiateCrawlRequest crawlRequest = new InitiateCrawlRequest();
        crawlRequest.setUrl(wireMockServer.baseUrl() + "/" + TEST_FILE_NAME);
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
        assertEquals(2, crawlResponse.documentsIngested, "Should report 2 documents ingested based on mock.");
        assertFalse(crawlResponse.streamIds.isEmpty(), "Stream IDs should be present.");
        assertEquals("test-stream-id-123", crawlResponse.streamIds.get(0));


        // Verify YappyIngestionService.ingestPipeDoc was called
        ArgumentCaptor<PipeDoc> pipeDocCaptor = ArgumentCaptor.forClass(PipeDoc.class);
        // sample-wiki-dump.xml contains 2 articles that should be processed by BlikiArticleExtractorProcessor
        verify(mockYappyIngestionService, times(2)).ingestPipeDoc(pipeDocCaptor.capture());

        // Verify some content of the captured PipeDocs
        PipeDoc capturedDoc1 = pipeDocCaptor.getAllValues().stream()
            .filter(pd -> pd.getTitle().equals("Test Article One"))
            .findFirst().orElse(null);
        assertNotNull(capturedDoc1, "PipeDoc for 'Test Article One' should have been ingested.");
        assertEquals("wiki_http://localhost/wiki/Test_Page_1", capturedDoc1.getId());

        PipeDoc capturedDoc2 = pipeDocCaptor.getAllValues().stream()
            .filter(pd -> pd.getTitle().equals("Category:Test Category"))
            .findFirst().orElse(null);
        assertNotNull(capturedDoc2, "PipeDoc for 'Category:Test Category' should have been ingested.");
        assertEquals("wiki_http://localhost/wiki/Test_Page_2", capturedDoc2.getId());
    }

    @Test
    void testInitiateCrawl_DownloadFails_ChecksumMismatch() {
        InitiateCrawlRequest crawlRequest = new InitiateCrawlRequest();
        crawlRequest.setUrl(wireMockServer.baseUrl() + "/" + TEST_FILE_NAME);
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

        verify(mockYappyIngestionService, times(0)).ingestPipeDoc(any(PipeDoc.class));
    }
}
