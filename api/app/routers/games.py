import logging

from fastapi import APIRouter, HTTPException

from app.models.schemas import Game, Channel
from app.services.mock_data import get_live_games, get_channels_for_game

router = APIRouter()
logger = logging.getLogger(__name__)


@router.get("/games/live", response_model=list[Game])
def list_live_games():
    games = get_live_games()
    logger.info("Returning %d live games", len(games))
    return games


@router.get("/games/{game_id}/channels", response_model=list[Channel])
def list_game_channels(game_id: str):
    channels = get_channels_for_game(game_id)
    if not channels:
        logger.warning("No channels found for game_id=%s", game_id)
        raise HTTPException(status_code=404, detail="Game not found or has no channels")
    logger.info("Returning %d channels for game_id=%s", len(channels), game_id)
    return channels
