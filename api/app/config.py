import os


def _get_bool(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _get_int(name: str, default: int) -> int:
    value = os.getenv(name)
    if value is None:
        return default
    try:
        return int(value)
    except ValueError:
        return default


STREAM_URL = os.getenv("INDIGO_STREAM_URL", "/streams/test")
STREAM_FORMAT = os.getenv("INDIGO_STREAM_FORMAT", "mp3")
STREAM_IS_LIVE = _get_bool("INDIGO_STREAM_IS_LIVE", True)
STREAM_RECOMMENDED_OFFSET_MS = _get_int("INDIGO_STREAM_RECOMMENDED_OFFSET_MS", 0)


def resolve_stream_url(base_url: str | None = None) -> str:
    if STREAM_URL.startswith(("http://", "https://")):
        return STREAM_URL

    if STREAM_URL.startswith("/") and base_url is not None:
        return f"{base_url.rstrip('/')}{STREAM_URL}"

    return STREAM_URL
