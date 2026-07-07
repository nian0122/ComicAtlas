FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY comic-common ./comic-common
COPY api-service ./api-service
COPY worker-service ./worker-service
COPY gateway ./gateway
RUN mvn clean package -pl comic-common,worker-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine-3.20
WORKDIR /app
COPY --from=builder /build/worker-service/target/*.jar app.jar
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "app.jar"]
