"""单进程单分析许可，无内部推理排队。"""

import asyncio
from contextlib import asynccontextmanager

from starlette.applications import Starlette
from starlette.requests import ClientDisconnect, Request
from starlette.responses import Response
from starlette.routing import Route

from ..application.analysis import Analyzer
from ..config import Config
from ..domain import jsoncodec, rules
from ..domain.errors import InvalidCandidate, InvalidInput, ProviderBusy, ProviderUnavailable
from ..domain.models import MAX_BODY_BYTES, MAX_RESPONSE_BYTES
from ..infrastructure.images import PillowInspector
from ..infrastructure.openai import ChatProvider
from ..infrastructure.uploads import BodyTooLarge, Upload


def reply(status, value):
    return Response(jsoncodec.encode(value), status_code=status,
                    media_type="application/json", headers={"Cache-Control": "no-store"})


def failure(status, code, message):
    return reply(status, {"error": {"code": code, "message": message}})


async def stop_task(task):
    if not task.done():
        task.cancel()
    while not task.done():
        try:
            await asyncio.shield(task)
        except asyncio.CancelledError:
            continue
        except Exception:
            break
    if not task.cancelled():
        task.exception()


async def watch_disconnect(request: Request):
    while True:
        if (await request.receive())["type"] == "http.disconnect":
            return


def build_app(config: Config):
    @asynccontextmanager
    async def lifespan(app):
        provider = ChatProvider(config) if config.configured else None
        app.state.analyzer = Analyzer(PillowInspector(), provider) if provider else None
        app.state.busy = False
        try:
            yield
        finally:
            if provider:
                await provider.close()

    async def live(_request):
        return reply(200, {"status": "up"})

    async def ready(_request):
        return reply(200 if config.configured else 503,
                     {"status": "ready" if config.configured else "unconfigured"})

    async def analyze(request: Request):
        if not config.configured:
            return failure(503, "AI_UNAVAILABLE", "AI 模型尚未配置")
        if request.app.state.busy:
            return failure(429, "AI_BUSY", "AI 服务繁忙")
        if (request.scope["query_string"] or
                len(request.headers.getlist("content-type")) != 1 or
                request.headers.get("content-encoding", "identity") != "identity"):
            return failure(400, "INVALID_INPUT", "AI 输入协议无效")
        request.app.state.busy = True
        upload = None
        try:
            async with asyncio.timeout(config.timeout):
                upload = Upload(request.headers["content-type"])
                async for chunk in request.stream():
                    upload.write(chunk)
                upload.finish()
                work = asyncio.create_task(request.app.state.analyzer.run(
                    upload.analysis, tuple(upload.files)))
                disconnected = asyncio.create_task(watch_disconnect(request))
                try:
                    done, _ = await asyncio.wait((work, disconnected),
                                                 return_when=asyncio.FIRST_COMPLETED)
                    if disconnected in done:
                        return Response(status_code=499)
                    value = rules.candidate_wire(await work)
                    # 输出预算同时保护严格协议和接收端资源。
                    payload = {"version": 1, "requestId": upload.analysis.request_id,
                               "candidate": value}
                    if len(jsoncodec.encode(payload)) > MAX_RESPONSE_BYTES:
                        raise InvalidCandidate()
                    return reply(200, payload)
                finally:
                    await stop_task(work)
                    await stop_task(disconnected)
        except BodyTooLarge:
            return failure(413, "INPUT_LIMIT", "AI 输入超过预算")
        except (InvalidInput, ValueError, UnicodeError):
            return failure(400, "INVALID_INPUT", "AI 输入无效")
        except InvalidCandidate:
            return failure(422, "INVALID_CANDIDATE", "模型候选不符合协议")
        except ProviderBusy:
            return failure(429, "AI_BUSY", "模型服务繁忙")
        except TimeoutError:
            return failure(504, "AI_TIMEOUT", "AI 分析超时")
        except (ProviderUnavailable, OSError):
            return failure(503, "AI_UNAVAILABLE", "AI 分析服务不可用")
        except ClientDisconnect:
            return Response(status_code=499)
        finally:
            if upload:
                upload.close()
            request.app.state.busy = False

    app = Starlette(routes=[Route("/health/live", live), Route("/health/ready", ready),
                            Route("/v1/analyses", analyze, methods=["POST"])],
                    lifespan=lifespan, max_body_size=MAX_BODY_BYTES)
    app.router.redirect_slashes = False
    return app
