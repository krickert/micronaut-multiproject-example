# This Dockerfile assumes you've already built the JAR locally
# Build with: ./gradlew :yappy-registration-cli:shadowJar

# Runtime stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy the pre-built JAR
COPY build/libs/*-all.jar app.jar

# Add a non-root user
RUN addgroup -g 1000 -S appgroup && \
    adduser -u 1000 -S appuser -G appgroup

# Create entrypoint script as root
RUN echo '#!/bin/sh' > /app/entrypoint.sh && \
    echo 'exec java -jar /app/app.jar "$@"' >> /app/entrypoint.sh && \
    chmod +x /app/entrypoint.sh

# Switch to non-root user
USER appuser

ENTRYPOINT ["/app/entrypoint.sh"]

# Default help command
CMD ["--help"]