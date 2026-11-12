"""应用只消费能力，不依赖 HTTP 或图片库实现。"""

from typing import BinaryIO, Protocol

from ..domain.models import Analysis, Candidate, Sample


class ImageInspector(Protocol):
    async def validate(self, sample: Sample, source: BinaryIO) -> None: ...


class ModelProvider(Protocol):
    async def analyze(self, analysis: Analysis, sources: tuple[BinaryIO, ...]) -> Candidate: ...
