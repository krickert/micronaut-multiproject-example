FROM eclipse-temurin:21-jre-alpine
WORKDIR /home/app

# Install supervisor and utilities
RUN apk add --no-cache supervisor bash curl wget

# Create directories  
RUN mkdir -p /app/engine /app/modules /var/log/supervisor /var/run

# Copy from layers
COPY --link layers/libs /home/app/libs
COPY --link layers/resources /home/app/resources
COPY --link layers/app /home/app/
COPY --link layers/project_libs /home/app/libs
COPY --link layers/snapshot_libs /home/app/libs

# Copy additional module and config files
COPY --link layers/engine/engine.jar /app/engine/engine.jar
COPY --link layers/modules/tika-parser.jar /app/modules/tika-parser.jar
COPY --link layers/config/supervisord.conf /etc/supervisor/conf.d/
COPY --link layers/config/application.yml /app/engine/
COPY --link layers/config/module-application.yml /app/modules/application.yml
COPY --link layers/config/start.sh /app/start.sh

# Make start script executable
RUN chmod +x /app/start.sh

# Expose ports: Engine HTTP, Engine gRPC, Tika Parser gRPC
EXPOSE 8080 50051 50053

ENTRYPOINT ["/app/start.sh"]