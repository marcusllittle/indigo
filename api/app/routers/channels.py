import logging

from fastapi import APIRouter, HTTPException, Request

from app.models.schemas import PlaybackInfo
from app.services.mock_data import get_playback_info

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/channels/{channel_id}/playback", response_model=PlaybackInfo)
def get_channel_playback(channel_id: str, request: Request):
    logger.info("Playback requested for channel_id=%s", channel_id)
    info = get_playback_info(channel_id, str(request.base_url))
    if not info:
        logger.warning("Playback lookup failed for channel_id=%s", channel_id)
        raise HTTPException(status_code=404, detail="Channel not found")
    return info
