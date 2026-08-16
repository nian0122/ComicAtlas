# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY comic-common/pom.xml comic-common/pom.xml
COPY comic-shared/pom.xml comic-shared/pom.xml
COPY api-service/pom.xml api-service/pom.xml
COPY reading-service/pom.xml reading-service/pom.xml
COPY worker-service/pom.xml worker-service/pom.xml
COPY gateway/pom.xml gateway/pom.xml
COPY comic-common/src comic-common/src
COPY comic-shared/src comic-shared/src
COPY reading-service/src reading-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -pl comic-common,comic-shared,reading-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine-3.20
WORKDIR /app
COPY --from=builder /build/reading-service/target/*.jar app.jar
EXPOSE 8011
ENTRYPOINT ["java", "-jar", "app.jar"]
