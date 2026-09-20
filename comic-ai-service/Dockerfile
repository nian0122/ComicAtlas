FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY comic-ai-service/pom.xml pom.xml
COPY comic-ai-service/src src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=builder /build/target/comic-ai-service-0.1.0-SNAPSHOT.jar app.jar
RUN mkdir -p /var/lib/comic-ai/work
EXPOSE 8020
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
