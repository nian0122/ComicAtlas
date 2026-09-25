# syntax=docker/dockerfile:1.7
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY config/maven/settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY comic-common/pom.xml comic-common/pom.xml
COPY comic-shared/pom.xml comic-shared/pom.xml
COPY api-service/pom.xml api-service/pom.xml
COPY comic-common/src comic-common/src
COPY comic-shared/src comic-shared/src
COPY api-service/src api-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -f comic-common/pom.xml install -Dmaven.test.skip=true -Dcheckstyle.skip=true
RUN --mount=type=cache,target=/root/.m2 mvn -B -f comic-shared/pom.xml install -Dmaven.test.skip=true -Dcheckstyle.skip=true
RUN --mount=type=cache,target=/root/.m2 mvn -B -f api-service/pom.xml package -Dmaven.test.skip=true -Dcheckstyle.skip=true

FROM eclipse-temurin:21-jre-alpine-3.20
WORKDIR /app
COPY --from=builder /build/api-service/target/*.jar app.jar
EXPOSE 8010
ENTRYPOINT ["java", "-jar", "app.jar"]
