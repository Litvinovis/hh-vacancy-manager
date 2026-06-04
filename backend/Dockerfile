FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY backend/target/hh-gui-*.jar app.jar
COPY config/ config/

RUN mkdir -p /app/data

VOLUME /app/data
VOLUME /app/config

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
