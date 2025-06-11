FROM eclipse-temurin:21-jre
WORKDIR /app
# Copy the built JAR from the orchestrator's build output
COPY ../../yappy-orchestrator/build/libs/*-all.jar app.jar

# Expose typical ports
EXPOSE 8080 50000

# Add environment variables for common configuration
ENV JAVA_OPTS="-Xmx1g -Xms512m"
ENV MICRONAUT_ENVIRONMENTS="docker"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]