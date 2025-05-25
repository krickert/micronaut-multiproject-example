package com.krickert.yappy.wikicrawler.processor;

import com.krickert.search.model.PipeDoc;
import com.krickert.search.model.wiki.Link;
import com.krickert.search.model.wiki.WikiArticle;
import com.krickert.search.model.wiki.WikiSiteInfo;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.util.Timestamps;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.stream.Collectors;

@Singleton
public class WikiArticleToPipeDocProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(WikiArticleToPipeDocProcessor.class);

    public PipeDoc transform(WikiArticle wikiArticle) {
        LOG.debug("Transforming WikiArticle ID: {} to PipeDoc", wikiArticle.getId());

        PipeDoc.Builder pipeDocBuilder = PipeDoc.newBuilder();

        // === Direct Mappings ===
        pipeDocBuilder.setId("wiki_" + wikiArticle.getSiteInfo().getBase() + "_" + wikiArticle.getId()); // Construct a unique ID
        if (wikiArticle.hasTitle()) {
            pipeDocBuilder.setTitle(wikiArticle.getTitle());
        }
        if (wikiArticle.hasText()) {
            pipeDocBuilder.setBody(wikiArticle.getText());
        }
        if (wikiArticle.hasRevisionId()) {
            pipeDocBuilder.setRevisionId(wikiArticle.getRevisionId());
        }

        // === Date Mappings ===
        // WikiArticle.timestamp (article's last revision/edit from the dump) -> PipeDoc.last_modified_date
        if (wikiArticle.hasTimestamp()) {
            pipeDocBuilder.setLastModifiedDate(wikiArticle.getTimestamp());
        }
        // WikiArticle.date_parsed (When Yappy processed this article) -> PipeDoc.processed_date
        if (wikiArticle.hasDateParsed()) {
            pipeDocBuilder.setProcessedDate(wikiArticle.getDateParsed());
        }
        // WikiArticle.dump_timestamp (Timestamp from the dump file itself) can go into custom_data

        // === Complex Mappings / Custom Data ===
        Struct.Builder customDataBuilder = Struct.newBuilder();

        customDataBuilder.putFields("wikipedia_article_id", Value.newBuilder().setStringValue(wikiArticle.getId()).build());
        customDataBuilder.putFields("wikipedia_namespace", Value.newBuilder().setStringValue(wikiArticle.getNamespace()).build());
        customDataBuilder.putFields("wikipedia_namespace_code", Value.newBuilder().setNumberValue(wikiArticle.getNamespaceCode()).build());
        customDataBuilder.putFields("wikipedia_wiki_type", Value.newBuilder().setStringValue(wikiArticle.getWikiType().toString()).build());

        if (wikiArticle.hasArticleVersion()) {
            customDataBuilder.putFields("wikipedia_article_version", Value.newBuilder().setStringValue(wikiArticle.getArticleVersion()).build());
        }
        if (wikiArticle.hasDumpTimestamp()) {
             customDataBuilder.putFields("wikipedia_dump_timestamp", Value.newBuilder().setStringValue(wikiArticle.getDumpTimestamp()).build());
        }

        // SiteInfo into custom_data
        if (wikiArticle.hasSiteInfo()) {
            WikiSiteInfo siteInfo = wikiArticle.getSiteInfo();
            Struct.Builder siteInfoStructBuilder = Struct.newBuilder();
            siteInfoStructBuilder.putFields("site_name", Value.newBuilder().setStringValue(siteInfo.getSiteName()).build());
            siteInfoStructBuilder.putFields("base_url", Value.newBuilder().setStringValue(siteInfo.getBase()).build());
            siteInfoStructBuilder.putFields("generator", Value.newBuilder().setStringValue(siteInfo.getGenerator()).build());
            siteInfoStructBuilder.putFields("character_case", Value.newBuilder().setStringValue(siteInfo.getCharacterCase()).build());
            customDataBuilder.putFields("wikipedia_site_info", Value.newBuilder().setStructValue(siteInfoStructBuilder.build()).build());
            
            // Construct source_uri
            // Example: "https://en.wikipedia.org/wiki/Page_Title"
            // Replace spaces with underscores for typical wiki URL format
            String pageTitleUrlPart = wikiArticle.getTitle().replace(" ", "_");
            pipeDocBuilder.setSourceUri("https://" + siteInfo.getBase() + "/wiki/" + pageTitleUrlPart);
        }

        // URL References into custom_data or keywords
        if (wikiArticle.getUrlReferencesCount() > 0) {
            // As a list of strings in custom_data
            Value.ListValue.Builder linksListBuilder = Value.ListValue.newBuilder();
            for (Link link : wikiArticle.getUrlReferencesList()) {
                linksListBuilder.addValues(Value.newBuilder().setStringValue(link.getUrl()).build());
            }
            customDataBuilder.putFields("wikipedia_url_references", Value.newBuilder().setListValue(linksListBuilder.build()).build());

            // Alternatively, as keywords
            // wikiArticle.getUrlReferencesList().forEach(link -> pipeDocBuilder.addKeywords("ref:" + link.getUrl()));
        }
        
        // Raw Wikitext into custom_data (optional, can be large)
        // customDataBuilder.putFields("wikipedia_raw_wikitext", Value.newBuilder().setStringValue(wikiArticle.getWikiText()).build());


        pipeDocBuilder.setCustomData(customDataBuilder.build());
        pipeDocBuilder.setDocumentType("wikipedia_article"); // Set document type

        LOG.debug("Successfully transformed WikiArticle ID: {} to PipeDoc ID: {}", wikiArticle.getId(), pipeDocBuilder.getId());
        return pipeDocBuilder.build();
    }
}
