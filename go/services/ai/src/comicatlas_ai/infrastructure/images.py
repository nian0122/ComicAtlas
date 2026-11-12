"""串行核对摘要与完整解码；线程结束前不释放临时文件。"""

import hashlib
from typing import BinaryIO

from PIL import Image, UnidentifiedImageError

from ..domain.errors import InvalidInput
from ..domain.models import MAX_PAGE_BYTES, Sample
from .blocking import owned_work


def _validate(sample: Sample, source: BinaryIO):
    try:
        source.seek(0)
        digest, size = hashlib.sha256(), 0
        while chunk := source.read(64 << 10):
            size += len(chunk)
            if size > MAX_PAGE_BYTES:
                raise InvalidInput()
            digest.update(chunk)
        if size != sample.size or digest.hexdigest() != sample.sha256:
            raise InvalidInput()
        source.seek(0)
        with Image.open(source, formats=("PNG", "JPEG", "WEBP")) as image:
            expected = {"PNG": "image/png", "JPEG": "image/jpeg", "WEBP": "image/webp"}
            if (expected.get(image.format) != sample.content_type or
                    image.size != (sample.width, sample.height) or
                    max(image.size) > 1600 or getattr(image, "n_frames", 1) != 1):
                raise InvalidInput()
            image.load()
        source.seek(0)
    except (OSError, ValueError, UnidentifiedImageError, Image.DecompressionBombError) as error:
        raise InvalidInput() from error


class PillowInspector:
    async def validate(self, sample: Sample, source: BinaryIO) -> None:
        await owned_work(_validate, sample, source)
