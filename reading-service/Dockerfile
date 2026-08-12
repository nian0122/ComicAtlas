FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY comic-common ./comic-common
COPY comic-contract ./comic-contract
COPY api-service ./api-service
COPY reading-service ./reading-service
COPY worker-service ./worker-service
COPY gateway ./gateway
RUN mvn clean package -pl comic-common,comic-contract,comic-persistence,reading-service -am -DskipTests

FROM eclipse-temurin:21-jre-alpine-3.20
WORKDIR /app
COPY --from=builder /build/reading-service/target/*.jar app.jar
EXPOSE 8011
ENTRYPOINT ["java", "-jar", "app.jar"]
