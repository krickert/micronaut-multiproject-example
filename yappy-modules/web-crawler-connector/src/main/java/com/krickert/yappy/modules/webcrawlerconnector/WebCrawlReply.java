package com.krickert.yappy.modules.webcrawlerconnector;

/**
 * Represents the result of crawling a web page.
 * This class contains the URL, title, body text, and HTML content of a web page.
 */
public record WebCrawlReply(
    String url,
    String title,
    String body,
    String html
) {
    /**
     * Builder for WebCrawlReply.
     */
    public static class Builder {
        private String url;
        private String title;
        private String body;
        private String html;

        /**
         * Sets the URL.
         *
         * @param url the URL
         * @return this builder
         */
        public Builder url(String url) {
            this.url = url;
            return this;
        }

        /**
         * Sets the title.
         *
         * @param title the title
         * @return this builder
         */
        public Builder title(String title) {
            this.title = title;
            return this;
        }

        /**
         * Sets the body text.
         *
         * @param body the body text
         * @return this builder
         */
        public Builder body(String body) {
            this.body = body;
            return this;
        }

        /**
         * Sets the HTML content.
         *
         * @param html the HTML content
         * @return this builder
         */
        public Builder html(String html) {
            this.html = html;
            return this;
        }

        /**
         * Builds a new WebCrawlReply.
         *
         * @return a new WebCrawlReply
         */
        public WebCrawlReply build() {
            return new WebCrawlReply(url, title, body, html);
        }
    }
}