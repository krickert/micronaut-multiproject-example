# Stage 1: Base image with Java 21 and Alpine Linux
FROM eclipse-temurin:21-jre-alpine

# Set a working directory
WORKDIR /app

# Install supervisor and bash for our start script
RUN apk add --no-cache supervisor bash

# --- This is the key change ---
# We now copy the *entire* content of the 'layers' directory created by Gradle.
# This single command brings in our 'engine', 'modules', and 'config' subdirectories.
COPY layers/ .

# Make the start script executable
RUN chmod +x /app/config/start.sh

# Expose all necessary ports
EXPOSE 8080 50051 50053

# The entrypoint is our simple start script, now located in /app/config/
ENTRYPOINT ["/app/config/start.sh"]