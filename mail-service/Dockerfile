FROM eclipse-temurin:21 as jre-build

WORKDIR /app

COPY target/mail-service-1.0-SNAPSHOT.jar app.jar

EXPOSE 8087

ENTRYPOINT ["java", "-jar", "app.jar"]