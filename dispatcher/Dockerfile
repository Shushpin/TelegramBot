
FROM eclipse-temurin:21 as jre-build

WORKDIR /app

COPY target/dispatcher-1.0-SNAPSHOT.jar app.jar


EXPOSE 8084

ENTRYPOINT ["java", "-jar", "app.jar"]