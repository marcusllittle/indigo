import logging
from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import StreamingResponse

router = APIRouter()
logger = logging.getLogger(__name__)

AUDIO_FILE = Path(__file__).resolve().parents[2] / "audio" / "test.mp3"
CHUNK_SIZE = 64 * 1024


def iter_audio_file():
    with AUDIO_FILE.open("rb") as audio_file:
        while chunk := audio_file.read(CHUNK_SIZE):
            yield chunk


@router.get("/streams/test", name="stream_test_audio")
def stream_test_audio():
    if not AUDIO_FILE.exists():
        logger.error("Test audio file not found: %s", AUDIO_FILE)
        raise HTTPException(status_code=404, detail="Test audio file not found")

    logger.info("Streaming test audio from %s", AUDIO_FILE)
    return StreamingResponse(
        iter_audio_file(),
        media_type="audio/mpeg",
        headers={"Cache-Control": "no-store"},
    )
