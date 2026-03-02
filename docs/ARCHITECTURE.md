# Indigo — Architecture

## System Overview

```
┌─────────────────────────┐       ┌─────────────────────────┐
│     Android App         │       │     FastAPI Backend      │
│  (Kotlin / Compose)     │──────▶│  (Python / Uvicorn)     │
│                         │ HTTP  │                         │
│  • UI (Compose)         │       │  • /games/live          │
│  • ExoPlayer (Media3)   │       │  • /games/{id}/channels │
│  • MediaSessionService  │       │  • /channels/{id}/play  │
│  • Retrofit API client  │       │  • /health              │
└──────────┬──────────────┘       └─────────────────────────┘
           │
           │ HLS / MP3 stream URL
           ▼
┌─────────────────────────┐
│   Audio Stream Source    │
│  (CDN / external URL)   │
└─────────────────────────┘
```

The system has two components:

1. **Android app** — the only client. Displays live games, controls commentary playback, handles audio via ExoPlayer.
2. **FastAPI backend** — serves game metadata, channel lists, and stream playback URLs. Does not transcode or host audio.

Audio streams are external URLs (HLS or progressive MP3). The backend returns stream URLs; the Android app plays them directly.

## Android Responsibilities

| Layer | What it does |
|---|---|
| **UI (Compose)** | Two screens: game list + game detail with commentary controls and sync |
| **ViewModel** | Holds UI state, calls repository, manages commentary/sync state |
| **Repository** | Wraps Retrofit calls, returns `Result<T>` |
| **Retrofit + OkHttp** | HTTP client hitting FastAPI backend |
| **AudioPlayerManager** | Wraps ExoPlayer — play, pause, stop, sync offset |
| **PlaybackService** | MediaSessionService for background audio + notification |

### Package structure

```
com.indigo.app/
├── data/
│   ├── api/          # Retrofit interface + client
│   ├── model/        # Kotlin data classes (serializable)
│   └── repository/   # GamesRepository
├── navigation/       # NavGraph (Compose Navigation)
├── player/           # AudioPlayerManager + PlaybackService
└── ui/
    ├── components/   # Shared composables (LiveBadge, etc.)
    ├── detail/       # GameDetailScreen + ViewModel
    ├── games/        # GamesListScreen + ViewModel
    └── theme/        # Colors, Typography, Theme
```

## Backend Responsibilities

| Layer | What it does |
|---|---|
| **Routers** | HTTP endpoints — games, channels, health |
| **Models** | Pydantic schemas for request/response |
| **Services** | Data layer — mock data now, real integrations later |

### Package structure

```
api/app/
├── main.py           # FastAPI app + CORS + router registration
├── routers/
│   ├── health.py     # GET /health
│   ├── games.py      # GET /games/live, GET /games/{id}/channels
│   └── channels.py   # GET /channels/{id}/playback
├── models/
│   └── schemas.py    # Game, Channel, PlaybackInfo
└── services/
    └── mock_data.py  # In-memory mock data
```

### API Contract

| Endpoint | Method | Returns |
|---|---|---|
| `/health` | GET | `{"status": "ok"}` |
| `/games/live` | GET | `List[Game]` |
| `/games/{gameId}/channels` | GET | `List[Channel]` |
| `/channels/{channelId}/playback` | GET | `PlaybackInfo` (includes `stream_url`) |

## Audio Stream Flow

1. User taps game → app calls `GET /games/{id}/channels` → gets channel list
2. User toggles commentary ON → app calls `GET /channels/{id}/playback` → gets `stream_url`
3. App passes `stream_url` to `AudioPlayerManager` → ExoPlayer loads and plays the stream
4. `PlaybackService` (MediaSessionService) keeps audio alive when app is backgrounded
5. Notification shows playback controls in the system notification shade

The app never touches video. Audio streams come from external URLs returned by the backend.

## Sync Model (v1)

Sync is **manual only** in v1.

- UI shows current offset in seconds (e.g., `+2.0s`, `-1.0s`)
- Five buttons: `-5s`, `-1s`, `Reset`, `+1s`, `+5s`
- Offset stored as `Long` (milliseconds) in ViewModel state
- On offset change, `AudioPlayerManager` seeks the ExoPlayer relative to current position by the delta
- `Reset` sets offset to `0` and seeks back to the live edge
- Backend can optionally return `recommended_offset_ms` in `PlaybackInfo` — applied as initial offset when commentary starts

### Limitations

- Seeking on a live stream may buffer briefly
- Very large offsets may go outside the DVR window (stream-dependent)
- No auto-sync — user adjusts by ear

**Deferred from MVP:** Audio fingerprint sync, timecode-based sync, server-side offset calculation.

## Known Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Test stream goes offline | Audio playback demo breaks | Keep 2-3 fallback stream URLs in mock data |
| ExoPlayer live seek limitations | Large offset may fail on some streams | Cap offset range in UI, document limitation |
| Background service killed by OEM | Audio stops on some Android devices | Standard MediaSessionService; document OEM battery settings |
| No auth | Anyone can call the API | Acceptable for MVP; add auth before public launch |
| Single backend instance | No HA / scaling | Acceptable for MVP; deploy behind reverse proxy later |
