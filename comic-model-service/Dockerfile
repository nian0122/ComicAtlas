FROM nvidia/cuda:12.8.1-base-ubuntu24.04

ENV DEBIAN_FRONTEND=noninteractive \
    PIP_NO_CACHE_DIR=1 \
    PIP_DEFAULT_TIMEOUT=120 \
    PIP_RETRIES=10 \
    PYTHONUNBUFFERED=1

RUN apt-get update \
        && apt-get install -y --no-install-recommends python3 python3-pip python3-venv python3-dev gcc g++ ca-certificates git \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m pip install --break-system-packages \
        --index-url https://pypi.tuna.tsinghua.edu.cn/simple \
        --trusted-host pypi.tuna.tsinghua.edu.cn \
        torch==2.7.1 torchvision==0.22.1 \
            lmdeploy==0.12.3 transformers==4.52.4 timm einops pillow==11.3.0 \
            openai_harmony aiohttp prometheus_client pybase64 xgrammar partial_json_parser

RUN python3 -m pip install --break-system-packages \
        --index-url https://pypi.tuna.tsinghua.edu.cn/simple \
        --trusted-host pypi.tuna.tsinghua.edu.cn \
        bitsandbytes

COPY comic-model-service/server.py /app/server.py

EXPOSE 23333
