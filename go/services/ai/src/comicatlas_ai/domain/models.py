"""协议值模型，不包含网络、文件或数据库操作。"""

from dataclasses import dataclass

MAX_SAMPLES = 24
MAX_PAGE_BYTES = 512 << 10
MAX_IMAGE_BYTES = 12 << 20
MAX_MANIFEST_BYTES = 256 << 10
MAX_RESPONSE_BYTES = 64 << 10
MAX_BODY_BYTES = MAX_IMAGE_BYTES + MAX_MANIFEST_BYTES + (128 << 10)
MAX_PROVIDER_BYTES = 256 << 10


@dataclass(frozen=True, slots=True)
class Term:
    id: str
    name: str


@dataclass(frozen=True, slots=True)
class Sample:
    media_id: str
    ordinal: int
    sha256: str
    size: int
    width: int
    height: int
    content_type: str
    field: str


@dataclass(frozen=True, slots=True)
class Analysis:
    request_id: str
    comic_id: str
    metadata_version: int
    structure_version: int
    samples: tuple[Sample, ...]
    categories: tuple[Term, ...]
    tags: tuple[Term, ...]


@dataclass(frozen=True, slots=True)
class TagCandidate:
    name: str
    existing_id: str | None
    evidence: tuple[int, ...]


@dataclass(frozen=True, slots=True)
class Candidate:
    title: str | None
    author: str | None
    description: str | None
    category_id: str | None
    tags: tuple[TagCandidate, ...]
    warnings: tuple[str, ...]
