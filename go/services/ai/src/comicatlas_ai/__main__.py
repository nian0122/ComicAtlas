"""唯一环境与进程入口，不在导入时启动服务或访问模型。"""

import os
import sys
import urllib.request

import uvicorn

from .config import load
from .transport.http import build_app


def main():
    if sys.argv[1:] == ["healthcheck"]:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open("http://127.0.0.1:8080/health/ready", timeout=2) as response:
            if response.status != 200:
                raise SystemExit(1)
        return
    if sys.argv[1:]:
        raise SystemExit("仅支持 healthcheck 子命令")
    try:
        config = load(os.environ.get)
    except ValueError:
        raise SystemExit("AI 服务配置无效") from None
    uvicorn.run(build_app(config), host="0.0.0.0", port=8080, workers=1,
                http="h11", loop="asyncio", ws="none", access_log=False,
                proxy_headers=False, server_header=False,
                limit_concurrency=8, timeout_keep_alive=5,
                timeout_graceful_shutdown=10, h11_max_incomplete_event_size=16384)


if __name__ == "__main__":
    main()
