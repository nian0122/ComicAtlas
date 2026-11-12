"""接收端独立校验 Go 协议，不信任模型或上传声明。"""

import re
import unicodedata

from . import jsoncodec
from .names import lower as lower_name
from .errors import InvalidCandidate, InvalidInput
from .models import (Analysis, Candidate, Sample, TagCandidate, Term,
                     MAX_IMAGE_BYTES, MAX_MANIFEST_BYTES, MAX_PAGE_BYTES,
                     MAX_RESPONSE_BYTES, MAX_SAMPLES)


def exact(value, keys):
    if type(value) is not dict or set(value) != set(keys):
        raise InvalidInput()
    return value


def text(value, maximum, *, multiline=False):
    if type(value) is not str or not value or value.strip() != value:
        raise InvalidInput()
    if len(value.encode("utf-8")) > maximum:
        raise InvalidInput()
    for char in value:
        if unicodedata.category(char) == "Cc" and not (multiline and char in "\n\t"):
            raise InvalidInput()
    return value


def integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise InvalidInput()
    return value


def array(value, maximum):
    if type(value) is not list or len(value) > maximum:
        raise InvalidInput()
    return value


def _terms(value, maximum):
    ids, names, terms = set(), set(), []
    for item in array(value, maximum):
        exact(item, {"id", "name"})
        identity, name = text(item["id"], 128), text(item["name"], 100)
        if identity in ids or lower_name(name) in names:
            raise InvalidInput()
        ids.add(identity)
        names.add(lower_name(name))
        terms.append(Term(identity, name))
    return tuple(terms)


def manifest(data: bytes) -> Analysis:
    value = exact(jsoncodec.decode(data, MAX_MANIFEST_BYTES), {
        "version", "requestId", "comicId", "metadataVersion", "structureVersion",
        "samples", "categories", "tags"})
    if integer(value["version"], 1, 1) != 1:
        raise InvalidInput()
    samples, ids, previous = [], set(), 0
    for index, item in enumerate(array(value["samples"], MAX_SAMPLES)):
        exact(item, {"mediaId", "ordinal", "sha256", "size", "width", "height",
                     "contentType", "field"})
        identity = text(item["mediaId"], 128)
        ordinal = integer(item["ordinal"], 1, 100000)
        digest = text(item["sha256"], 64)
        if (identity in ids or ordinal <= previous or
                re.fullmatch(r"[0-9a-f]{64}", digest) is None or
                item["field"] != f"image_{index + 1:02d}" or
                item["contentType"] not in ("image/png", "image/jpeg", "image/webp")):
            raise InvalidInput()
        samples.append(Sample(identity, ordinal, digest,
                              integer(item["size"], 1, MAX_PAGE_BYTES),
                              integer(item["width"], 1, 1600),
                              integer(item["height"], 1, 1600),
                              item["contentType"], item["field"]))
        ids.add(identity)
        previous = ordinal
    if not samples or sum(sample.size for sample in samples) > MAX_IMAGE_BYTES:
        raise InvalidInput()
    categories, tags = _terms(value["categories"], 200), _terms(value["tags"], 1000)
    if sum(len(term.id.encode()) + len(term.name.encode())
           for term in categories + tags) > 128 << 10:
        raise InvalidInput()
    return Analysis(text(value["requestId"], 128), text(value["comicId"], 128),
                    integer(value["metadataVersion"], 1, 2**63 - 1),
                    integer(value["structureVersion"], 1, 2**63 - 1),
                    tuple(samples), categories, tags)


def candidate(data: bytes, analysis: Analysis) -> Candidate:
    try:
        value = exact(jsoncodec.decode(data, MAX_RESPONSE_BYTES, nodes=4096),
                      {"title", "author", "description", "categoryId", "tags", "warnings"})
        for key, maximum in (("title", 500), ("author", 2000), ("description", 8192)):
            if value[key] is not None:
                text(value[key], maximum, multiline=key == "description")
        if value["categoryId"] is not None:
            if text(value["categoryId"], 128) not in {term.id for term in analysis.categories}:
                raise InvalidInput()
        by_id = {term.id: term.name for term in analysis.tags}
        by_name = {lower_name(term.name) for term in analysis.tags}
        ordinals = {sample.ordinal for sample in analysis.samples}
        tags, seen = [], set()
        for item in array(value["tags"], 30):
            exact(item, {"name", "existingId", "evidence"})
            name = text(item["name"], 100)
            existing = item["existingId"]
            if lower_name(name) in seen:
                raise InvalidInput()
            if existing is None:
                if lower_name(name) in by_name:
                    raise InvalidInput()
            elif by_id.get(text(existing, 128)) != name:
                raise InvalidInput()
            evidence = tuple(integer(number, 1, 100000)
                             for number in array(item["evidence"], len(ordinals)))
            if not evidence or tuple(sorted(set(evidence))) != evidence or not set(evidence) <= ordinals:
                raise InvalidInput()
            seen.add(lower_name(name))
            tags.append(TagCandidate(name, existing, evidence))
        warnings = tuple(text(warning, 500) for warning in array(value["warnings"], 20))
        return Candidate(value["title"], value["author"], value["description"],
                         value["categoryId"], tuple(tags), warnings)
    except (InvalidInput, UnicodeError, TypeError) as error:
        raise InvalidCandidate() from error


def candidate_wire(value: Candidate):
    return {"title": value.title, "author": value.author,
            "description": value.description, "categoryId": value.category_id,
            "tags": [{"name": tag.name, "existingId": tag.existing_id,
                      "evidence": list(tag.evidence)} for tag in value.tags],
            "warnings": list(value.warnings)}
