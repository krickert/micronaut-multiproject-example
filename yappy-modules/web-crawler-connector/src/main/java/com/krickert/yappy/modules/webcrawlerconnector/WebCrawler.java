package com.krickert.yappy.modules.webcrawlerconnector;

import com.google.common.collect.Maps;
import com.google.common.net.InternetDomainName;
import com.krickert.search.model.Blob;
import com.krickert.search.model.PipeDoc;
import com.krickert.yappy.modules.webcrawlerconnector.config.WebCrawlerConfig;
import io.github.bonigarcia.wdm.WebDriverManager;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Web crawler that uses Selenium to crawl web pages.
 * This class provides methods to crawl web pages and extract their content.
 */
@Singleton
public class WebCrawler {
    private static final Logger LOG = LoggerFactory.getLogger(WebCrawler.class);

    private final WebCrawlerConfig config;
    private final ResourceResolver resourceResolver;

    /**
     * Constructs a new WebCrawler.
     *
     * @param config the web crawler configuration
     * @param resourceResolver the resource resolver
     */
    @Inject
    public WebCrawler(WebCrawlerConfig config, ResourceResolver resourceResolver) {
        this.config = config;
        this.resourceResolver = resourceResolver;
    }

    /**
     * Crawls a web page and returns the crawled data.
     *
     * @param url the URL to crawl
     * @return a map of URLs to WebCrawlReply objects
     */
    public Map<String, WebCrawlReply> crawl(String url) {
        WebDriver driver = setupWebDriver();
        try {
            Map<String, WebCrawlReply> results = new ConcurrentHashMap<>();
            Set<String> visitedUrls = Collections.synchronizedSet(new HashSet<>());
            AtomicInteger pageCount = new AtomicInteger(0);

            crawlPage(driver, url, 0, results, visitedUrls, pageCount, new URL(url));

            return results;
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL: {}", url, e);
            throw new IllegalArgumentException("Invalid URL: " + url, e);
        } finally {
            driver.quit();
        }
    }

    /**
     * Sets up the WebDriver with the configured options.
     *
     * @return the configured WebDriver
     */
    private WebDriver setupWebDriver() {
        WebDriverManager.chromedriver().setup();
        ChromeOptions options = new ChromeOptions();

        // Add uBlock extension if configured
        if (config.uBlockExtensionPath() != null && !config.uBlockExtensionPath().isEmpty()) {
            Optional<URL> resource = resourceResolver.getLoader(ClassPathResourceLoader.class)
                    .flatMap(loader -> loader.getResource(config.uBlockExtensionPath()));

            if (resource.isPresent()) {
                options.addExtensions(new File(resource.get().getFile()));
            } else {
                LOG.warn("uBlock extension not found at path: {}", config.uBlockExtensionPath());
            }
        }

        // Set headless mode
        if (config.headless()) {
            options.addArguments("--headless=new");
        }

        // Set user agent if configured
        if (config.userAgent() != null && !config.userAgent().isEmpty()) {
            options.addArguments("--user-agent=" + config.userAgent());
        }

        WebDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(config.timeoutSeconds()));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(config.timeoutSeconds() / 2));

        return driver;
    }

    /**
     * Crawls a page and its links recursively.
     *
     * @param driver the WebDriver
     * @param url the URL to crawl
     * @param depth the current crawl depth
     * @param results the map to store results in
     * @param visitedUrls the set of visited URLs
     * @param pageCount the counter for pages visited
     * @param startUrl the starting URL
     */
    private void crawlPage(WebDriver driver, String url, int depth, Map<String, WebCrawlReply> results, 
                          Set<String> visitedUrls, AtomicInteger pageCount, URL startUrl) {
        // Check if we've reached the maximum depth or page count
        if (depth > config.maxDepth() || pageCount.get() >= config.maxPages() || visitedUrls.contains(url)) {
            return;
        }

        // Mark this URL as visited
        visitedUrls.add(url);
        pageCount.incrementAndGet();

        try {
            LOG.info("{}Crawling URL: {} (depth: {}, page: {})", 
                    config.logPrefix(), url, depth, pageCount.get());

            // Load the page
            driver.get(url);

            // Extract content
            String title = config.extractTitle() ? driver.getTitle() : "";
            String bodyText = config.extractText() ? driver.findElement(By.tagName("body")).getText() : "";
            String html = config.extractHtml() ? driver.getPageSource() : "";

            // Store the result
            WebCrawlReply reply = new WebCrawlReply.Builder()
                    .url(url)
                    .title(title)
                    .body(bodyText)
                    .html(html)
                    .build();

            results.put(url, reply);

            // If we're at max depth, don't crawl links
            if (depth >= config.maxDepth()) {
                return;
            }

            // Find all links on the page
            List<WebElement> links = driver.findElements(By.tagName("a"));

            // Get the domain of the start URL
            InternetDomainName startDomain = InternetDomainName.from(startUrl.getHost());
            String topDomain = startDomain.hasPublicSuffix() ? 
                    startDomain.topPrivateDomain().toString() : startDomain.toString();

            // Process each link
            for (WebElement link : links) {
                String href = link.getAttribute("href");

                // Skip if the link is null or we've already visited it
                if (href == null || visitedUrls.contains(href)) {
                    continue;
                }

                // Check if we should stay within the same domain
                if (config.stayWithinDomain() && !isSameDomain(href, topDomain)) {
                    continue;
                }

                // Recursively crawl the linked page
                crawlPage(driver, href, depth + 1, results, visitedUrls, pageCount, startUrl);

                // Check if we've reached the maximum page count
                if (pageCount.get() >= config.maxPages()) {
                    break;
                }
            }
        } catch (Exception e) {
            LOG.error("{}Error crawling URL: {}", config.logPrefix(), url, e);
        }
    }

    /**
     * Checks if a URL is from the same domain as the expected domain.
     *
     * @param url the URL to check
     * @param expectedDomain the expected domain
     * @return true if the URL is from the same domain, false otherwise
     */
    private boolean isSameDomain(String url, String expectedDomain) {
        try {
            URL netUrl = new URL(url);
            InternetDomainName domainName = InternetDomainName.from(netUrl.getHost());
            return domainName.hasPublicSuffix() && 
                   domainName.topPrivateDomain().toString().equals(expectedDomain);
        } catch (MalformedURLException e) {
            LOG.warn("{}Invalid URL in href: {}", config.logPrefix(), url, e);
            return false;
        }
    }

    /**
     * Creates a PipeDoc from a WebCrawlReply.
     *
     * @param reply the WebCrawlReply
     * @return the PipeDoc
     */
    public PipeDoc createPipeDoc(WebCrawlReply reply) {
        PipeDoc.Builder builder = PipeDoc.newBuilder();

        // Set basic fields
        builder.setId(UUID.randomUUID().toString());
        builder.setSourceUri(reply.url());
        builder.setTitle(reply.title());
        builder.setBody(reply.body());
        builder.setDocumentType("web_page");
        builder.setProcessedDate(com.google.protobuf.Timestamp.newBuilder()
                .setSeconds(Instant.now().getEpochSecond())
                .setNanos(Instant.now().getNano())
                .build());

        // Add HTML as blob if available
        if (reply.html() != null && !reply.html().isEmpty()) {
            Blob blob = Blob.newBuilder()
                    .setData(com.google.protobuf.ByteString.copyFrom(reply.html().getBytes()))
                    .setMimeType("text/html")
                    .putMetadata("url", reply.url())
                    .build();
            builder.setBlob(blob);
        }

        return builder.build();
    }
}
