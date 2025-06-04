FROM eclipse-temurin:21-jre-alpine

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl wget

# Create user
RUN adduser -D -s /bin/bash appuser

# Create directories
RUN mkdir -p /app/engine /app/modules /var/log/supervisor /var/run && \
    chown -R appuser:appuser /app /var/log/supervisor

# Copy JARs (built on host)
COPY yappy-engine/build/libs/*-all.jar /app/engine/engine.jar
COPY yappy-modules/test-connector/build/libs/*-all.jar /app/modules/test-connector.jar

# Copy configuration files
COPY yappy-containers/engine-test-connector/src/main/resources/supervisord.conf /etc/supervisor/conf.d/
COPY yappy-containers/engine-test-connector/src/main/resources/application.yml /app/engine/
COPY yappy-containers/engine-test-connector/src/main/resources/module-application.yml /app/modules/
COPY yappy-containers/engine-test-connector/src/main/resources/start.sh /app/start.sh

# Make start script executable
RUN chmod +x /app/start.sh
# Expose ports: Engine HTTP, Engine gRPC, Test Connector gRPC
EXPOSE 8080 50051 50059

USER root
CMD ["/app/start.sh"]