"""Chat Completions 视觉协议适配；上游地址和模型均来自配置。"""

import base64
import tempfile

import httpx

from .blocking import owned_work
from ..config import Config
from ..domain import jsoncodec, prompt, rules
from ..domain.errors import InvalidCandidate, InvalidInput, ProviderBusy, ProviderUnavailable
from ..domain.models import Analysis, MAX_PAGE_BYTES, MAX_PROVIDER_BYTES


def _write_body(output, config: Config, analysis: Analysis, sources):
    # JSON 落临时文件，避免同时持有整组 Base64、JSON 字符串与发送缓冲。
    output.write(b'{"model":' + jsoncodec.encode(config.model))
    output.write(b',"stream":false,"response_format":{"type":"json_object"},"messages":[')
    output.write(jsoncodec.encode({"role": "system", "content": prompt.SYSTEM}))
    output.write(b',{"role":"user","content":[')
    output.write(jsoncodec.encode({"type": "text", "text": prompt.context(analysis)}))
    for sample, source in zip(analysis.samples, sources, strict=True):
        output.write(b',' + jsoncodec.encode({"type": "text", "text": f"图片序号 {sample.ordinal}"}))
        source.seek(0)
        data = source.read(MAX_PAGE_BYTES + 1)
        if len(data) != sample.size:
            raise InvalidInput()
        image = base64.b64encode(data).decode("ascii")
        output.write(b',' + jsoncodec.encode({"type": "image_url", "image_url": {
            "url": f"data:{sample.content_type};base64,{image}", "detail": "auto"}}))
    output.write(b']}] }')
    if output.tell() > 18 << 20:
        raise InvalidInput()
    output.seek(0)


class ChatProvider:
    def __init__(self, config: Config):
        self.config = config
        limits = httpx.Limits(max_connections=1, max_keepalive_connections=1)
        self.client = httpx.AsyncClient(
            timeout=config.timeout, trust_env=False, follow_redirects=False,
            transport=httpx.AsyncHTTPTransport(retries=0, limits=limits, trust_env=False))

    async def close(self):
        await self.client.aclose()

    async def analyze(self, analysis: Analysis, sources):
        if not self.config.configured:
            raise ProviderUnavailable()
        headers = {"Content-Type": "application/json", "Accept": "application/json",
                   "Accept-Encoding": "identity"}
        if self.config.api_key:
            headers["Authorization"] = "Bearer " + self.config.api_key
        try:
            with tempfile.TemporaryFile(mode="w+b") as body:
                await owned_work(_write_body, body, self.config, analysis, sources)
                body.seek(0, 2)
                headers["Content-Length"] = str(body.tell())
                body.seek(0)

                async def chunks():
                    while data := await owned_work(body.read, 64 << 10):
                        yield data

                async with self.client.stream("POST", self.config.provider_url + "/chat/completions",
                                              content=chunks(), headers=headers) as response:
                    if response.status_code == 429:
                        raise ProviderBusy()
                    if response.status_code != 200:
                        raise ProviderUnavailable()
                    if (response.headers.get("content-type", "").split(";")[0] != "application/json" or
                            response.headers.get("content-encoding", "identity") != "identity"):
                        raise InvalidCandidate()
                    data = bytearray()
                    async for chunk in response.aiter_raw(chunk_size=64 << 10):
                        if len(data) + len(chunk) > MAX_PROVIDER_BYTES:
                            raise InvalidCandidate()
                        data.extend(chunk)
                return self._candidate(bytes(data), analysis)
        except httpx.TimeoutException as error:
            raise TimeoutError() from error
        except httpx.HTTPError as error:
            raise ProviderUnavailable() from error

    @staticmethod
    def _candidate(data, analysis):
        try:
            value = jsoncodec.decode(data, MAX_PROVIDER_BYTES)
            choices = value["choices"]
            if type(choices) is not list or len(choices) != 1:
                raise InvalidCandidate()
            choice = choices[0]
            if type(choice) is not dict:
                raise InvalidCandidate()
            message = choice["message"]
            if type(message) is not dict:
                raise InvalidCandidate()
            if (choice.get("finish_reason") != "stop" or message.get("role") != "assistant" or
                    message.get("refusal") or message.get("tool_calls") or
                    type(message.get("content")) is not str):
                raise InvalidCandidate()
            return rules.candidate(message["content"].encode("utf-8"), analysis)
        except (KeyError, TypeError, UnicodeError, InvalidInput) as error:
            raise InvalidCandidate() from error
