"""取消后等待有界文件/图片工作退出，避免提前关闭它持有的资源。"""

import asyncio


async def owned_work(function, *arguments):
    work = asyncio.create_task(asyncio.to_thread(function, *arguments))
    try:
        return await asyncio.shield(work)
    except asyncio.CancelledError:
        while not work.done():
            try:
                await asyncio.shield(work)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        if not work.cancelled():
            work.exception()
        raise
