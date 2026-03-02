import logging

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import channels, games, health, streams

logging.basicConfig(level=logging.INFO)

app = FastAPI(title="Indigo API", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(health.router)
app.include_router(games.router)
app.include_router(channels.router)
app.include_router(streams.router)
