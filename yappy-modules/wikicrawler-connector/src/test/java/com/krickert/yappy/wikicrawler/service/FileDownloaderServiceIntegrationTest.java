package com.krickert.yappy.wikicrawler.service;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.krickert.search.model.wiki.DownloadFileRequest;
import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.ErrorCheck;
import com.krickert.search.model.wiki.ErrorCheckType;
import com.krickert.yappy.wikicrawler.service.storage.LocalStorageService;
import com.krickert.yappy.wikicrawler.service.storage.StorageService;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@Property(name = "micronaut.http.services.default.pool.enabled", value = "false") // Optional: simplify client for single test
class FileDownloaderServiceIntegrationTest {

    @Inject
    FileDownloaderService fileDownloaderService;

    @Inject
    StorageService storageService; // Should be LocalStorageService instance

    private WireMockServer wireMockServer;

    @TempDir
    Path tempDir; // JUnit 5 temporary directory for base storage

    private Path configuredBaseStoragePath;

    private static final String TEST_FILE_CONTENT = "This is a test file for download.";
    private static final String TEST_FILE_NAME = "test-dump.txt";
    private static final String TEST_FILE_PATH_ON_SERVER = "/downloads/" + TEST_FILE_NAME;
    private static final String TEST_DUMP_DATE = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
    private String expectedMd5Checksum;

    @BeforeEach
    void setUp() throws Exception {
        // Configure LocalStorageService to use a subdirectory within @TempDir
        // This assumes LocalStorageService can be reconfigured or we test its default behavior from application-test.yml
        // For this test, let's rely on the application-test.yml path but ensure it's clean.
        configuredBaseStoragePath = Path.of("build/tmp/wikicrawler-test-downloads"); // From application-test.yml
        if (storageService instanceof LocalStorageService) {
            // If we could directly set the path on the injected bean, that would be an option.
            // Otherwise, ensure the path from application-test.yml is used and cleaned.
             deleteDirectoryRecursively(configuredBaseStoragePath); // Clean up before test
        }
        Files.createDirectories(configuredBaseStoragePath);


        wireMockServer = new WireMockServer(0); // Random port
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
        System.setProperty("wiremock.server.port", String.valueOf(wireMockServer.port())); // For application-test.yml

        // Calculate MD5 checksum for test content
        MessageDigest md = MessageDigest.getInstance("MD5");
        md.update(TEST_FILE_CONTENT.getBytes(StandardCharsets.UTF_8));
        byte[] digest = md.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            hexString.append(String.format("%02x", b));
        }
        expectedMd5Checksum = hexString.toString();

        // Stub WireMock
        stubFor(get(urlEqualTo(TEST_FILE_PATH_ON_SERVER))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "text/plain")
                        .withBody(TEST_FILE_CONTENT)));
    }
    
    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                 .sorted(java.util.Comparator.reverseOrder())
                 .forEach(p -> {
                     try {
                         Files.delete(p);
                     } catch (IOException e) {
                         // Handle error or log
                     }
                 });
        }
    }


    @AfterEach
    void tearDown() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
        // Optional: clean up downloaded files if not using @TempDir effectively for LocalStorageService
        // try {
        //     deleteDirectoryRecursively(configuredBaseStoragePath.resolve(TEST_DUMP_DATE));
        // } catch (IOException e) {
        //     // log
        // }
    }

    @Test
    void testDownloadFile_Success_MD5Checksum() throws IOException {
        DownloadFileRequest request = DownloadFileRequest.newBuilder()
                .setUrl(wireMockServer.baseUrl() + TEST_FILE_PATH_ON_SERVER)
                .setFileName(TEST_FILE_NAME)
                .setFileDumpDate(TEST_DUMP_DATE)
                .setErrorCheck(ErrorCheck.newBuilder()
                        .setErrorCheckType(ErrorCheckType.MD5)
                        .setErrorCheck(expectedMd5Checksum).build())
                .addAllExpectedFilesInDump(Collections.emptyList())
                .build();

        DownloadedFile downloadedFile = fileDownloaderService.downloadFile(request).block(); // Using block for test simplicity

        assertNotNull(downloadedFile);
        assertEquals(TEST_FILE_NAME, downloadedFile.getFileName());
        assertEquals(expectedMd5Checksum.toLowerCase(), downloadedFile.getErrorCheck().getErrorCheck().toLowerCase());
        assertEquals(ErrorCheckType.MD5, downloadedFile.getErrorCheck().getErrorCheckType());
        assertEquals(TEST_DUMP_DATE, downloadedFile.getFileDumpDate());
        assertTrue(downloadedFile.getDownloadEnd().getSeconds() >= downloadedFile.getDownloadStart().getSeconds());

        // Verify file storage
        Path expectedFinalPath = configuredBaseStoragePath.resolve(TEST_DUMP_DATE).resolve(TEST_FILE_NAME);
        assertTrue(Files.exists(expectedFinalPath), "Downloaded file should exist at " + expectedFinalPath);
        String actualContent = Files.readString(expectedFinalPath);
        assertEquals(TEST_FILE_CONTENT, actualContent);

        // Verify access URI
        assertFalse(downloadedFile.getAccessUrisList().isEmpty());
        String accessUri = downloadedFile.getAccessUris(0);
        assertTrue(accessUri.startsWith("file:/"), "Access URI should start with file:/");
        assertTrue(accessUri.endsWith(TEST_DUMP_DATE + "/" + TEST_FILE_NAME), "Access URI should end with date/filename part: " + accessUri);
        
        // Verify .incomplete file was removed (i.e., does not exist)
        Path incompleteFilePath = configuredBaseStoragePath.resolve(TEST_DUMP_DATE).resolve(TEST_FILE_NAME + ".incomplete");
        assertFalse(Files.exists(incompleteFilePath), ".incomplete file should have been removed/renamed.");
    }
}
