FROM eclipse-temurin:21 as jre-build

WORKDIR /app

COPY target/node-1.0-SNAPSHOT.jar app.jar

EXPOSE 8085

ENTRYPOINT ["java", "-jar", "app.jar"]