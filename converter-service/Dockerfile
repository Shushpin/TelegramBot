
FROM eclipse-temurin:21 as jre-build

WORKDIR /app

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ffmpeg \
    libreoffice \
    fonts-liberation \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*


COPY target/converter-service-1.0-SNAPSHOT.jar app.jar

EXPOSE 8083

ENTRYPOINT ["java", "-jar", "app.jar"]