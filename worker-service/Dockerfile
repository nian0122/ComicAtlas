# syntax=docker/dockerfile:1.7
FROM golang:1.22-bookworm AS image-optimizer-builder
WORKDIR /build/image-optimizer
COPY worker-service/tools/image-optimizer/go.mod worker-service/tools/image-optimizer/go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY worker-service/tools/image-optimizer/*.go ./
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/image-optimizer .

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
COPY worker-service/src worker-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -B package -pl comic-common,worker-service -am -DskipTests

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install --no-install-recommends -y \
        ca-certificates \
        ffmpeg \
        libwebp7 \
    && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/worker-service/target/*-exec.jar app.jar
COPY --from=image-optimizer-builder /out/image-optimizer /opt/comicatlas/tools/image-optimizer
RUN chmod 0755 /opt/comicatlas/tools/image-optimizer
EXPOSE 8020
ENTRYPOINT ["java", "-jar", "app.jar"]
