FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY config/maven/settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY comic-ai-service/pom.xml comic-ai-service/pom.xml
COPY comic-ai-service/src comic-ai-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B -f comic-ai-service/pom.xml package -Dmaven.test.skip=true -Dcheckstyle.skip=true

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/comic-ai-service/target/comic-ai-service-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /var/lib/comic-ai/work
EXPOSE 8020
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
