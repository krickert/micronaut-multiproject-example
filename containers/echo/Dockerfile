FROM eclipse-temurin:21-jre
WORKDIR /app
# Copy the built JAR from the module's build output
COPY ../../yappy-modules/echo/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]