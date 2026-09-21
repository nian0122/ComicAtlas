FROM nvidia/cuda:12.8.1-base-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 python3-pip python3-venv ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && python3 -m pip install --break-system-packages --upgrade pip \
    && python3 -m pip install --break-system-packages --extra-index-url https://download.pytorch.org/whl/cu128 lmdeploy==0.17.0 \
    && python3 -m pip install --break-system-packages timm einops

EXPOSE 23333

ENTRYPOINT ["lmdeploy"]
