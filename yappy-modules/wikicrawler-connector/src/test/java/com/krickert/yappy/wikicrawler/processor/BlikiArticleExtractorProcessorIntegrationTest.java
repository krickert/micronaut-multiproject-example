package com.krickert.yappy.wikicrawler.processor;

import com.krickert.search.model.wiki.DownloadedFile;
import com.krickert.search.model.wiki.WikiArticle;
import com.krickert.search.model.wiki.WikiType;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
class BlikiArticleExtractorProcessorIntegrationTest {

    @Inject
    BlikiArticleExtractorProcessor articleExtractor;

    @TempDir
    Path tempDir; // For optional proto dump output

    @Test
    void testParseWikiDump_Success() throws IOException, URISyntaxException {
        Path resourcePath = Paths.get(getClass().getClassLoader().getResource("sample-wiki-dump.xml").toURI());

        DownloadedFile downloadedFile = DownloadedFile.newBuilder()
                .setFileName("sample-wiki-dump.xml")
                .addAccessUris(resourcePath.toUri().toString()) // "file:///path/to/sample-wiki-dump.xml"
                .setFileDumpDate("20230101") // Example dump date
                .build();

        List<WikiArticle> extractedArticles = new ArrayList<>();
        articleExtractor.parseWikiDump(downloadedFile, extractedArticles::add);

        assertFalse(extractedArticles.isEmpty(), "Should extract articles");
        // Expecting Test Article One and Category:Test Category. Redirects might be skipped by default Bliki setup or need specific handling.
        // The current BlikiArticleExtractorProcessor processes Main and Category.
        assertEquals(2, extractedArticles.size(), "Should extract two articles (main and category)");

        // --- Verify Article One ---
        Optional<WikiArticle> articleOneOpt = extractedArticles.stream()
                .filter(a -> a.getTitle().equals("Test Article One")).findFirst();
        assertTrue(articleOneOpt.isPresent(), "Test Article One should be extracted");
        WikiArticle articleOne = articleOneOpt.get();

        assertEquals("1", articleOne.getId());
        assertEquals("Test Article One", articleOne.getTitle());
        assertTrue(articleOne.getText().contains("This is the content of Test Article One"), "Plain text content mismatch");
        assertTrue(articleOne.getWikiText().contains("[[Link2|link to another page]]"), "Wikitext content mismatch");
        assertEquals(0, articleOne.getNamespaceCode());
        assertEquals("", articleOne.getNamespace()); // Main namespace often represented as empty string by Bliki
        assertEquals("101", articleOne.getRevisionId());
        assertEquals("101", articleOne.getArticleVersion()); // article_version from revision_id
        assertEquals(WikiType.ARTICLE, articleOne.getWikiType());
        assertEquals(Instant.parse("2023-01-15T10:00:00Z").getEpochSecond(), articleOne.getTimestamp().getSeconds());
        assertTrue(articleOne.getDateParsed().getSeconds() > 0); // Should be recent
        assertEquals("Test Wiki", articleOne.getSiteInfo().getSiteName());

        // --- Verify Category Article ---
        Optional<WikiArticle> categoryArticleOpt = extractedArticles.stream()
                .filter(a -> a.getTitle().equals("Category:Test Category")).findFirst();
        assertTrue(categoryArticleOpt.isPresent(), "Category:Test Category should be extracted");
        WikiArticle categoryArticle = categoryArticleOpt.get();

        assertEquals("2", categoryArticle.getId());
        assertEquals("Category:Test Category", categoryArticle.getTitle());
        assertTrue(categoryArticle.getText().contains("This category is for testing."), "Category text mismatch");
        assertEquals(14, categoryArticle.getNamespaceCode());
        assertEquals("Category", categoryArticle.getNamespace());
        assertEquals("202", categoryArticle.getRevisionId());
        assertEquals("202", categoryArticle.getArticleVersion());
        assertEquals(WikiType.CATEGORY, categoryArticle.getWikiType());
        assertEquals(Instant.parse("2023-01-16T11:00:00Z").getEpochSecond(), categoryArticle.getTimestamp().getSeconds());

        // Optional: Dump extracted articles to a binary file for testing next stage
        dumpArticlesToFile(extractedArticles, tempDir.resolve("extracted-articles.bin"));
    }

    private void dumpArticlesToFile(List<WikiArticle> articles, Path outputPath) throws IOException {
        if (articles.isEmpty()) return;
        try (OutputStream out = new FileOutputStream(outputPath.toFile())) {
            for (WikiArticle article : articles) {
                article.writeDelimitedTo(out); // Writes size before message
            }
        }
        System.out.println("Dumped " + articles.size() + " extracted articles to: " + outputPath.toAbsolutePath());
    }
}
