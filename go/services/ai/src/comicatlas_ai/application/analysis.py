"""所有样本通过核对后才允许调用模型。"""

from typing import BinaryIO

from .ports import ImageInspector, ModelProvider
from ..domain.errors import InvalidInput
from ..domain.models import Analysis, Candidate


class Analyzer:
    def __init__(self, images: ImageInspector, provider: ModelProvider):
        self.images = images
        self.provider = provider

    async def run(self, analysis: Analysis, sources: tuple[BinaryIO, ...]) -> Candidate:
        if len(sources) != len(analysis.samples):
            raise InvalidInput()
        for sample, source in zip(analysis.samples, sources, strict=True):
            await self.images.validate(sample, source)
        return await self.provider.analyze(analysis, sources)
