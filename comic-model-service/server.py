"""InternVL3.5 量化推理服务，提供 OpenAI 兼容视觉接口。"""

import base64
import io
import os
import threading
import uuid
from datetime import UTC, datetime
from typing import Any

import torch
from fastapi import FastAPI, HTTPException
from PIL import Image
from pydantic import BaseModel
from transformers import AutoModel, AutoTokenizer
from torchvision import transforms
from torchvision.transforms.functional import InterpolationMode


MODEL_PATH = os.getenv("MODEL_PATH", "/models/InternVL3_5-2B")
MODEL_NAME = os.getenv("AI_MODEL", "OpenGVLab/InternVL3_5-2B")
IMAGE_SIZE = 448
IMAGE_MEAN = (0.485, 0.456, 0.406)
IMAGE_STD = (0.229, 0.224, 0.225)

image_transform = transforms.Compose(
    [
        transforms.Resize((IMAGE_SIZE, IMAGE_SIZE), interpolation=InterpolationMode.BICUBIC),
        transforms.ToTensor(),
        transforms.Normalize(IMAGE_MEAN, IMAGE_STD),
    ]
)

print(f"正在加载量化模型: {MODEL_PATH}", flush=True)
tokenizer = AutoTokenizer.from_pretrained(MODEL_PATH, trust_remote_code=True, use_fast=False)
model = AutoModel.from_pretrained(
    MODEL_PATH,
    trust_remote_code=True,
    load_in_8bit=True,
    device_map={"": "cuda:0"},
    low_cpu_mem_usage=True,
)
model.eval()
generation_lock = threading.Lock()
print("InternVL3.5-2B 量化模型已就绪", flush=True)

app = FastAPI(title="ComicAtlas Local Vision Model")


class ChatRequest(BaseModel):
    model: str | None = None
    messages: list[dict[str, Any]]
    max_tokens: int | None = 256
    temperature: float | None = 0.2


def decode_image(image_url: str) -> Image.Image:
    if not image_url.startswith("data:"):
        raise HTTPException(status_code=400, detail="本地模型只接受 data URL 图片")
    try:
        encoded_data = image_url.split(",", 1)[1]
        return Image.open(io.BytesIO(base64.b64decode(encoded_data))).convert("RGB")
    except (ValueError, OSError) as exception:
        raise HTTPException(status_code=400, detail="图片 data URL 无法解析") from exception


def prepare_messages(messages: list[dict[str, Any]]) -> tuple[torch.Tensor | None, str, int]:
    image_tensors: list[torch.Tensor] = []
    text_parts: list[str] = []
    for message in messages:
        content = message.get("content", "")
        if isinstance(content, str):
            text_parts.append(content)
            continue
        if not isinstance(content, list):
            continue
        for item in content:
            if item.get("type") == "text":
                text_parts.append(str(item.get("text", "")))
            elif item.get("type") in ("image_url", "image"):
                image_data = item.get("image_url", item)
                image_url = image_data.get("url") if isinstance(image_data, dict) else image_data
                if image_url:
                    image_tensors.append(image_transform(decode_image(image_url)))
    if not image_tensors:
        return None, "\n".join(text_parts), 0
    question = "\n".join(f"<image>\n{part}" for part in text_parts)
    pixel_values = torch.stack(image_tensors).half().to("cuda:0")
    return pixel_values, question, len(image_tensors)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "model": MODEL_NAME}


@app.get("/v1/models")
def models() -> dict[str, Any]:
    return {
        "object": "list",
        "data": [{"id": MODEL_NAME, "object": "model", "owned_by": "ComicAtlas"}],
    }


@app.post("/v1/chat/completions")
def chat_completions(request: ChatRequest) -> dict[str, Any]:
    pixel_values, question, image_count = prepare_messages(request.messages)
    generation_config = {
        "num_beams": 1,
        "max_new_tokens": max(1, min(request.max_tokens or 256, 1024)),
        "do_sample": (request.temperature or 0.0) > 0,
        "temperature": request.temperature or 0.2,
    }
    with generation_lock:
        answer = model.chat(tokenizer, pixel_values, question, generation_config)
    prompt_tokens = len(tokenizer.encode(question, add_special_tokens=False))
    completion_tokens = len(tokenizer.encode(answer, add_special_tokens=False))
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": int(datetime.now(UTC).timestamp()),
        "model": request.model or MODEL_NAME,
        "choices": [{"index": 0, "message": {"role": "assistant", "content": answer}, "finish_reason": "stop"}],
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
            "image_count": image_count,
        },
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=23333, log_level="info")
