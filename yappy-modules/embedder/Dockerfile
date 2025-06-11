FROM eclipse-temurin:21-jre

WORKDIR /home/app

# Copy application layers
COPY layers/libs /home/app/libs
COPY layers/project_libs /home/app/libs
COPY layers/app /home/app/
COPY layers/resources /home/app/resources

# Expose only gRPC port
EXPOSE 50051

# Set environment variables
ENV GRPC_SERVER_PORT=50051

ENTRYPOINT ["java", "-jar", "/home/app/application.jar"]