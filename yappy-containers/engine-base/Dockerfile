FROM eclipse-temurin:21-jre-alpine AS base

# Install supervisor and other base dependencies
RUN apk add --no-cache supervisor bash curl wget

# Create app user
RUN adduser -D -s /bin/bash appuser

# Create directories
RUN mkdir -p /app/engine /app/modules /var/log/supervisor /var/run && \
    chown -R appuser:appuser /app /var/log/supervisor

# Copy base supervisor config (will be extended by specific containers)
COPY src/main/resources/supervisord-base.conf /etc/supervisor/supervisord.conf

# Set working directory
WORKDIR /app

# Expose common ports
EXPOSE 8080 50051

# Base health check (can be overridden)
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1