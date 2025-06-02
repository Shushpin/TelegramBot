FROM eclipse-temurin:21 as jre-build

WORKDIR /app

COPY target/rest-service-1.0-SNAPSHOT.jar app.jar

EXPOSE 8086

ENTRYPOINT ["java", "-jar", "app.jar"]