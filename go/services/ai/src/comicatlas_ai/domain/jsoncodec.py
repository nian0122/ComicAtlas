"""严格、有字节和结构预算的 JSON。"""

import json

from .errors import InvalidInput


def _pairs(items):
    value = {}
    for key, item in items:
        if key in value:
            raise InvalidInput()
        value[key] = item
    return value


def _constant(_value):
    raise InvalidInput()


def decode(data: bytes, maximum: int, *, nodes: int = 16384):
    if len(data) > maximum:
        raise InvalidInput()
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=_pairs,
                           parse_constant=_constant)
        pending = [(value, 0)]
        remaining = nodes
        while pending:
            item, depth = pending.pop()
            remaining -= 1
            if remaining < 0 or depth > 8:
                raise InvalidInput()
            if isinstance(item, dict):
                for key, child in item.items():
                    key.encode("utf-8")
                    pending.append((child, depth + 1))
            elif isinstance(item, list):
                pending.extend((child, depth + 1) for child in item)
            elif isinstance(item, str):
                item.encode("utf-8")
        return value
    except (ValueError, UnicodeError, RecursionError) as error:
        raise InvalidInput() from error


def encode(value) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"),
                      allow_nan=False).encode("utf-8")
