package com.krickert.yappy.wikicrawler.kafka;

import com.krickert.search.model.wiki.WikiArticle;
import com.krickert.search.model.wiki.WikiSiteInfo;
import com.krickert.search.model.wiki.WikiType;
import com.google.protobuf.Timestamp;
import io.micronaut.configuration.kafka.annotation.KafkaListener;
import io.micronaut.configuration.kafka.annotation.OffsetReset;
import io.micronaut.configuration.kafka.annotation.Topic;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest(transactional = false) // Disable transactions for Kafka tests
@Property(name = "spec.name", value = "WikiArticleProducerIntegrationTest") // Unique name for test context
// Ensure Kafka test resources are active, apicurio.registry.url should be picked up from application-test.yml via test resources
// Forcing Apicurio Protobuf serde for the test producer/consumer if not globally default
@Property(name = "kafka.producers.wikicrawler-article-producer.value-serializer", value = "io.apicurio.registry.serde.kafka.ProtobufKafkaSerializer")
@Property(name = "kafka.consumers.test-wiki-article-listener.value-deserializer", value = "io.apicurio.registry.serde.kafka.ProtobufKafkaDeserializer")
@Property(name = "kafka.consumers.test-wiki-article-listener.specific-protobuf-key-type", value = "com.krickert.search.model.wiki.WikiArticle")
class WikiArticleProducerIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(WikiArticleProducerIntegrationTest.class);

    @Inject
    WikiArticleProducer articleProducer;

    @Inject
    TestWikiArticleListener articleListener; // Inject our test listener

    @Test
    void testSendWikiArticle_Success() throws InterruptedException {
        Instant now = Instant.now();
        WikiArticle testArticle = WikiArticle.newBuilder()
                .setId("kafka-test-123")
                .setTitle("Kafka Test Article")
                .setText("This article is for Kafka integration testing.")
                .setWikiText("== Kafka Test ==")
                .setNamespaceCode(0)
                .setNamespace("Main")
                .setDumpTimestamp("20230101")
                .setRevisionId("revK1")
                .setArticleVersion("revK1")
                .setDateParsed(Timestamp.newBuilder().setSeconds(now.getEpochSecond()).setNanos(now.getNano()).build())
                .setTimestamp(Timestamp.newBuilder().setSeconds(now.getEpochSecond() - 3600).setNanos(now.getNano()).build()) // An hour ago
                .setSiteInfo(WikiSiteInfo.newBuilder().setSiteName("TestKafkaWiki").setBase("kafka.test.org").build())
                .setWikiType(WikiType.ARTICLE)
                .build();

        articleProducer.sendWikiArticle(testArticle.getId(), testArticle);
        LOG.info("Sent test article ID: {} to Kafka", testArticle.getId());

        // Wait for the listener to receive the message
        // The BlockingQueue in the listener helps make this deterministic for tests.
        WikiArticle receivedArticle = articleListener.receivedMessages.poll(15, TimeUnit.SECONDS); // Adjust timeout as needed

        assertNotNull(receivedArticle, "Article should have been received from Kafka");
        assertEquals(testArticle.getId(), receivedArticle.getId(), "ID mismatch");
        assertEquals(testArticle.getTitle(), receivedArticle.getTitle(), "Title mismatch");
        assertEquals(testArticle.getText(), receivedArticle.getText(), "Text mismatch");
        assertEquals(testArticle.getRevisionId(), receivedArticle.getRevisionId(), "Revision ID mismatch");
        assertEquals(testArticle.getSiteInfo().getSiteName(), receivedArticle.getSiteInfo().getSiteName(), "Site name mismatch");

        // Key verification is implicit if listener receives it on correct topic partition (if key-based partitioning is active)
        // and if our listener is specific enough. We can also capture the key in the listener if needed.
    }

    // Test Kafka Listener Component
    @Requires(property = "spec.name", value = "WikiArticleProducerIntegrationTest") // Only activate for this test
    @KafkaListener(
        groupId = "test-wiki-article-listener-group", // Unique group ID for test consumer
        offsetReset = OffsetReset.EARLIEST
    )
    public static class TestWikiArticleListener {
        private static final Logger LISTENER_LOG = LoggerFactory.getLogger(TestWikiArticleListener.class);
        public final BlockingQueue<WikiArticle> receivedMessages = new LinkedBlockingQueue<>();

        // Topic is read from application-test.yml: kafka.topic.wiki.article
        @Topic("${kafka.topic.wiki.article}")
        public void receive(WikiArticle article) { // Micronaut can also receive ConsumerRecord<String, WikiArticle> to get the key
            LISTENER_LOG.info("TestListener received article with ID: {}", article.getId());
            receivedMessages.offer(article);
        }
    }
}
