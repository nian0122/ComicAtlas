"""仅入口传入环境读取器；配置不在异常或 repr 中显示凭据。"""

from dataclasses import dataclass, field
from urllib.parse import urlsplit


@dataclass(frozen=True, slots=True)
class Config:
    provider_url: str = ""
    model: str = ""
    api_key: str = field(default="", repr=False)
    timeout: float = 300.0

    @property
    def configured(self):
        return bool(self.provider_url and self.model)


def load(getenv):
    base = getenv("COMICATLAS_AI_PROVIDER_URL", "").strip()
    model = getenv("COMICATLAS_AI_MODEL", "").strip()
    key = getenv("COMICATLAS_AI_PROVIDER_KEY", "").strip()
    try:
        timeout = float(getenv("COMICATLAS_AI_TIMEOUT_SECONDS", "300"))
        if not 0.001 <= timeout <= 1800:
            raise ValueError()
        if base:
            parsed = urlsplit(base)
            if (len(base) > 2048 or parsed.scheme not in ("http", "https") or
                    not parsed.hostname or parsed.username is not None or
                    parsed.password is not None or parsed.query or parsed.fragment or
                    "?" in base or "#" in base or "%" in parsed.path or
                    parsed.port is not None and not 1 <= parsed.port <= 65535):
                raise ValueError()
        if len(model.encode()) > 200 or len(key.encode()) > 4096 or not key.isascii():
            raise ValueError()
        if any(ord(char) < 32 or ord(char) == 127 for char in model + key + base):
            raise ValueError()
    except (ValueError, UnicodeError) as error:
        raise ValueError("AI 服务配置无效") from error
    return Config(base.rstrip("/"), model, key, timeout)
