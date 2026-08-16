FROM golang:1.22-bookworm AS image-optimizer-builder
WORKDIR /build/image-optimizer
COPY worker-service/tools/image-optimizer/go.mod worker-service/tools/image-optimizer/go.sum ./
RUN go mod download
COPY worker-service/tools/image-optimizer/*.go ./
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/image-optimizer .

FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY settings.xml /root/.m2/settings.xml
COPY pom.xml .
COPY comic-common ./comic-common
COPY comic-shared ./comic-shared
COPY api-service ./api-service
COPY reading-service ./reading-service
COPY worker-service ./worker-service
COPY gateway ./gateway
RUN mvn clean package -pl comic-common,worker-service -am -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        aria2 \
        ca-certificates \
        ffmpeg \
        libwebp7 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/worker-service/target/*-exec.jar app.jar
COPY --from=image-optimizer-builder /out/image-optimizer /opt/comicatlas/tools/image-optimizer
RUN chmod 0755 /opt/comicatlas/tools/image-optimizer
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "app.jar"]
