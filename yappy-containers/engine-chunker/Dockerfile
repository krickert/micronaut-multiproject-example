# Dockerfile for engine+chunker container
FROM eclipse-temurin:21-jre-alpine

# Install supervisor
RUN apk add --no-cache supervisor bash curl wget

# Create app user
RUN adduser -D -s /bin/bash appuser

# Create directories
RUN mkdir -p /app/engine /app/modules /var/log/supervisor /var/run && \
    chown -R appuser:appuser /app /var/log/supervisor

# Copy pre-built JARs from host
COPY yappy-engine/build/libs/*-all.jar /app/engine/engine.jar
COPY yappy-modules/chunker/build/libs/*-all.jar /app/modules/chunker.jar

# Copy configuration files
COPY yappy-containers/engine-chunker/src/main/resources/supervisord.conf /etc/supervisor/conf.d/
COPY yappy-containers/engine-chunker/src/main/resources/application.yml /app/engine/
COPY yappy-containers/engine-chunker/src/main/resources/module-application.yml /app/modules/

# Expose ports
# 8080: Engine HTTP/REST
# 50051: Engine gRPC  
# 50053: Chunker module gRPC
EXPOSE 8080 50051 50053

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:8080/health || exit 1

# Start supervisor
USER root
CMD ["/usr/bin/supervisord", "-c", "/etc/supervisor/conf.d/supervisord.conf"]