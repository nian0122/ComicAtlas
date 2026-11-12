# syntax=docker/dockerfile:1.7
FROM golang:1.27.3-bookworm@sha256:972b4110ba0f47539a975c31e52c64765e1ff19f2d74469ae4a595c6676e80a5 AS tools-build
RUN sed -i "s|http://deb.debian.org|https://deb.debian.org|g" /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends nasm=2.16.01-1 xz-utils && rm -rf /var/lib/apt/lists/*
WORKDIR /tool-source
RUN curl -fsSL --retry 3 https://deb.debian.org/debian/pool/main/x/x264/x264_0.164.3095+gitbaee400.orig.tar.gz -o x264.tar.gz \
    && echo "8b237e94b08c196a1da22f2f25875f10be4cff3648df4eeff21e00da8f683fc2  x264.tar.gz" | sha256sum -c - \
    && mkdir x264 && tar -xzf x264.tar.gz -C x264 --strip-components=1 \
    && cd x264 && ./configure --prefix=/opt/x264 --enable-static --disable-cli --disable-opencl --bit-depth=8 --chroma-format=420 \
    && (make -j2 >/tmp/x264-build.log 2>&1 || (tail -80 /tmp/x264-build.log; exit 1)) \
    && make install >/dev/null \
    && mkdir -p /opt/media-tools/share/licenses \
    && cp COPYING /opt/media-tools/share/licenses/x264-COPYING
RUN curl -4 -fsSL --retry 5 --retry-delay 2 --retry-all-errors https://github.com/FFmpeg/nv-codec-headers/releases/download/n11.1.5.3/nv-codec-headers-11.1.5.3.tar.gz -o nv-codec-headers.tar.gz \
    && echo "2974b91062197e0527dffa3aadd8fe3bfa6681ae45f5ff9181bc0ca6479abd59  nv-codec-headers.tar.gz" | sha256sum -c - \
    && mkdir nv-codec-headers && tar -xzf nv-codec-headers.tar.gz -C nv-codec-headers --strip-components=1 \
    && make -C nv-codec-headers install PREFIX=/opt/ffnvcodec
RUN curl -fsSL --retry 3 https://deb.debian.org/debian/pool/main/f/ffmpeg/ffmpeg_5.1.9.orig.tar.xz -o ffmpeg.tar.xz \
    && echo "d9b593bb2ba93d4b50f74177e0cdcd41747e708596367deed0c30348a71dd176  ffmpeg.tar.xz" | sha256sum -c - \
    && tar -xJf ffmpeg.tar.xz --strip-components=1 \
    && PKG_CONFIG_PATH=/opt/x264/lib/pkgconfig:/opt/ffnvcodec/lib/pkgconfig ./configure --prefix=/opt/media-tools --disable-autodetect --disable-everything \
       --enable-gpl --enable-libx264 --enable-ffnvcodec --enable-nvenc --pkg-config-flags=--static \
       --disable-network --disable-doc --disable-debug --enable-small \
       --enable-ffmpeg --enable-ffprobe --enable-protocol=file \
       --enable-demuxer=mov,matroska,avi --enable-muxer=mp4,matroska,webm,image2 \
       --enable-decoder=h264,hevc,vp8,vp9,mpeg4,mjpeg,aac,mp3,opus,vorbis,rawvideo,pcm_s16le \
       --enable-parser=h264,hevc,vp8,vp9,mpeg4video,aac,mpegaudio,opus,vorbis \
       --enable-encoder=mpeg4,libx264,h264_nvenc,aac,mjpeg --enable-indev=lavfi --enable-filter=color,format,scale,pad,aresample,anull,sine \
    && (make -j2 >/tmp/media-tools-build.log 2>&1 || (tail -80 /tmp/media-tools-build.log; exit 1)) \
    && make install >/dev/null \
    && mkdir -p /opt/media-tools/share/licenses \
    && cp COPYING.LGPLv2.1 COPYING.LGPLv3 COPYING.GPLv2 COPYING.GPLv3 LICENSE.md /opt/media-tools/share/licenses/

FROM tools-build AS image-tools-build
RUN apt-get -o Acquire::Retries=3 update && apt-get install -y --no-install-recommends cmake=3.25.1-1 libpng-dev && rm -rf /var/lib/apt/lists/*
WORKDIR /image-source
RUN curl -fsSL --retry 3 https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/3.2.0/libjpeg-turbo-3.2.0.tar.gz -o jpeg.tar.gz \
    && echo "6f30092cef9fb839779646608f4ee14ae3cbac989c47fa05e841b0841f09878e  jpeg.tar.gz" | sha256sum -c - \
    && mkdir jpeg && tar -xzf jpeg.tar.gz -C jpeg --strip-components=1 \
    && cmake -S jpeg -B jpeg-build -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/opt/image-tools \
       -DENABLE_SHARED=FALSE -DENABLE_STATIC=TRUE -DWITH_TURBOJPEG=FALSE -DWITH_TOOLS=TRUE -DWITH_TESTS=FALSE \
    && (cmake --build jpeg-build --target djpeg-static -j2 >/tmp/jpeg-build.log 2>&1 || (tail -80 /tmp/jpeg-build.log; exit 1)) \
    && mkdir -p /opt/image-tools/bin /opt/image-tools/share/licenses \
    && cp jpeg-build/djpeg-static /opt/image-tools/bin/djpeg \
    && strip /opt/image-tools/bin/djpeg \
    && cp jpeg/LICENSE.md /opt/image-tools/share/licenses/jpeg-LICENSE.md \
    && cp jpeg/README.ijg /opt/image-tools/share/licenses/jpeg-README.ijg \
    && cp jpeg/src/spng/LICENSE /opt/image-tools/share/licenses/jpeg-spng-LICENSE \
    && cp jpeg/src/spng/libpng-LICENSE.txt /opt/image-tools/share/licenses/jpeg-libpng-LICENSE.txt
RUN curl -fsSL --retry 3 https://storage.googleapis.com/downloads.webmproject.org/releases/webp/libwebp-1.6.0.tar.gz -o webp.tar.gz \
    && echo "e4ab7009bf0629fd11982d4c2aa83964cf244cffba7347ecd39019a9e38c4564  webp.tar.gz" | sha256sum -c - \
    && mkdir webp && tar -xzf webp.tar.gz -C webp --strip-components=1 \
    && cd webp && ./configure --prefix=/opt/image-tools --disable-shared --enable-static \
       --disable-gl --disable-sdl --disable-tiff --disable-gif --disable-jpeg --disable-wic LDFLAGS=-static LIBS="-lz -lm" \
    && (make -j2 LDFLAGS=-all-static >/tmp/webp-build.log 2>&1 || (tail -80 /tmp/webp-build.log; exit 1)) \
    && grep -q "#define WEBP_HAVE_PNG 1" src/webp/config.h \
    && cp examples/cwebp /opt/image-tools/bin/cwebp \
    && strip /opt/image-tools/bin/cwebp \
    && cp COPYING /opt/image-tools/share/licenses/webp-COPYING \
    && cp PATENTS /opt/image-tools/share/licenses/webp-PATENTS \
    && cp /usr/share/doc/libpng-dev/copyright /opt/image-tools/share/licenses/libpng-copyright \
    && cp /usr/share/doc/zlib1g-dev/copyright /opt/image-tools/share/licenses/zlib-copyright \
    && if readelf -d /opt/image-tools/bin/cwebp | grep -q NEEDED; then exit 1; fi

FROM golang:1.27.3-bookworm@sha256:972b4110ba0f47539a975c31e52c64765e1ff19f2d74469ae4a595c6676e80a5 AS dev
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends zip=3.0-13 7zip=22.01+really26.02+dfsg-0+deb12u1 && rm -rf /var/lib/apt/lists/*
# 绑定源码由宿主用户持有；仅信任开发容器的固定工作区。
RUN git config --system --add safe.directory /workspace
COPY --from=tools-build /opt/media-tools/bin /opt/media-tools/bin
COPY --from=tools-build /opt/media-tools/share/licenses /opt/media-tools/share/licenses
COPY --from=image-tools-build /opt/image-tools/bin /opt/image-tools/bin
COPY --from=image-tools-build /opt/image-tools/share/licenses /opt/image-tools/share/licenses
ENV COMICATLAS_TEST_REQUIRE_MEDIA_TOOLS=1
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
CMD ["go", "run", "./cmd/dev", "check"]

FROM dev AS build
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/comicatlas ./cmd/comicatlas

FROM debian:bookworm-slim@sha256:7ff6ba31527b9e336162fa061746c366c4b2032f4d2cff2078bea43f78efe3f8 AS runtime
COPY --from=image-tools-build /opt/image-tools/bin /opt/image-tools/bin
COPY --from=image-tools-build /opt/image-tools/share/licenses /opt/image-tools/share/licenses
COPY --from=tools-build /opt/media-tools/bin /opt/media-tools/bin
COPY --from=tools-build /opt/media-tools/share/licenses /opt/media-tools/share/licenses
COPY --from=build /out/comicatlas /app/comicatlas
COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN mkdir -p /data /backups /volume && chown 10001:10001 /data /backups /volume
USER 10001:10001
WORKDIR /app
ENV COMICATLAS_HTTP_ADDR=0.0.0.0:8080 COMICATLAS_DATA_DIR=/data
ENV COMICATLAS_IMPORT_DIR=/volume/imports COMICATLAS_MEDIA_DIR=/volume/media
ENV COMICATLAS_FFPROBE_PATH=/opt/media-tools/bin/ffprobe
ENV COMICATLAS_FFMPEG_PATH=/opt/media-tools/bin/ffmpeg
ENV COMICATLAS_CWEBP_PATH=/opt/image-tools/bin/cwebp COMICATLAS_DJPEG_PATH=/opt/image-tools/bin/djpeg
EXPOSE 8080 8081
ENTRYPOINT ["/app/comicatlas"]
