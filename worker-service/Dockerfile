FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache aria2
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "app.jar"]
