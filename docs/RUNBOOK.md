# Indigo — Runbook

## Backend (FastAPI)

### Prerequisites

- Python 3.12
- pip

### Setup

```bash
cd api
python3.12 -m venv venv
source venv/bin/activate   # Windows: venv\Scripts\activate
pip install -r requirements.txt
```

### Run (Development)

```bash
cd api
source venv/bin/activate
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

API will be available at `http://localhost:8000`.
Docs at `http://localhost:8000/docs` (Swagger UI).

### Stream Configuration

The backend playback URL is configurable via environment variables.

Supported variables:
- `INDIGO_STREAM_URL`
  - Default: `/streams/test`
  - Accepts:
    - a backend-relative path like `/streams/test`
    - an absolute MP3/HLS URL like `https://example.com/live.mp3`
    - an absolute HLS playlist like `https://example.com/live.m3u8`
- `INDIGO_STREAM_FORMAT`
  - Default: `mp3`
  - Typical values: `mp3`, `hls`
- `INDIGO_STREAM_IS_LIVE`
  - Default: `true`
  - Accepts `true/false`, `1/0`, `yes/no`, `on/off`
- `INDIGO_STREAM_RECOMMENDED_OFFSET_MS`
  - Default: `0`
  - Integer number of milliseconds applied as the initial sync offset in Android

Examples:

Use the backend-hosted local test stream:

```bash
cd api
source venv/bin/activate
INDIGO_STREAM_URL=/streams/test \
INDIGO_STREAM_FORMAT=mp3 \
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Use an external MP3 live feed:

```bash
cd api
source venv/bin/activate
INDIGO_STREAM_URL=https://example.com/live.mp3 \
INDIGO_STREAM_FORMAT=mp3 \
INDIGO_STREAM_RECOMMENDED_OFFSET_MS=0 \
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

Use an HLS feed:

```bash
cd api
source venv/bin/activate
INDIGO_STREAM_URL=https://example.com/live.m3u8 \
INDIGO_STREAM_FORMAT=hls \
INDIGO_STREAM_IS_LIVE=true \
INDIGO_STREAM_RECOMMENDED_OFFSET_MS=2000 \
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### Mock Mode vs Live Mode

The backend runs in **mock mode by default**. All data is served from `app/services/mock_data.py`.

To switch to live integrations (future):
- Set environment variable `INDIGO_MODE=live`
- Implement real data sources in corresponding service modules
- Mock mode remains the fallback

### Verify

```bash
curl http://localhost:8000/health
# {"status":"ok"}

curl http://localhost:8000/games/live
# Returns list of mock live games

curl -D - -o /dev/null http://localhost:8000/streams/test
# Returns 200 plus audio/mpeg headers for the local test stream
```

### Test Audio Stream

The mock playback endpoint now returns a backend-hosted stream URL for
`GET /streams/test`, which serves `api/audio/test.mp3` using FastAPI's
`StreamingResponse`.

To swap in a different local test file:

```bash
cp /path/to/your/test-audio.mp3 api/audio/test.mp3
```

To move from the local file to a real live commentary feed later:
- Set `INDIGO_STREAM_URL` to the real feed URL before starting the backend
- Set `INDIGO_STREAM_FORMAT` to `mp3` or `hls`
- Optionally set `INDIGO_STREAM_RECOMMENDED_OFFSET_MS`
- Or wire a real source into `app/routers/streams.py`
- Common options:
  - Icecast or Shoutcast serving MP3/AAC
  - RTMP ingest transcoded to HLS
  - Any CDN-backed HLS live audio playlist

For Android emulator testing, the backend returns playback URLs using the
request host, so the app receives `http://10.0.2.2:8000/...` when it calls
the API from the emulator.

### Tonight: Live Mic Commentary With Icecast

Fastest practical local stack for tonight:
- Icecast on Windows as the local relay server
- Windows `ffmpeg` as the mic capture + MP3 encoder
- Indigo backend pointed at the Icecast mount via env vars

Why this stack:
- It preserves the exact model `Mic -> Encoder -> Local streaming server -> Indigo`
- `ffmpeg` is already available on this machine
- Icecast gives you a real stream URL the Android app can play immediately
- No app redesign or backend code changes are needed

#### 1. Install and configure Icecast on Windows

Preferred install path:
- Download the official Windows installer from `https://icecast.org/download/`
- Install it to `C:\Icecast`

If you prefer Chocolatey and it works on your machine:

```powershell
choco install icecast -y
```

Edit:

```text
C:\Icecast\etc\icecast.xml
```

Keep the default file and change only the minimum:

```xml
<authentication>
    <source-password>indigo-source</source-password>
    <relay-password>indigo-relay</relay-password>
    <admin-user>admin</admin-user>
    <admin-password>indigo-admin</admin-password>
</authentication>

<hostname>localhost</hostname>

<listen-socket>
    <port>8001</port>
</listen-socket>
```

Start Icecast from Windows:

```powershell
cd C:\Icecast
.\icecast.exe -c .\etc\icecast.xml
```

Open this in a Windows browser to confirm the server is up:

```text
http://127.0.0.1:8001/
```

#### 2. Publish your mic to Icecast with ffmpeg

Windows `ffmpeg` is already available at:

```text
C:\ProgramData\chocolatey\bin\ffmpeg.exe
```

List DirectShow devices:

```powershell
& "C:\ProgramData\chocolatey\bin\ffmpeg.exe" -list_devices true -f dshow -i dummy
```

Look for your microphone name and replace `YOUR_MIC_NAME` below.

Publish live mic audio to Icecast:

```powershell
& "C:\ProgramData\chocolatey\bin\ffmpeg.exe" `
  -f dshow -i audio="YOUR_MIC_NAME" `
  -ac 1 -ar 44100 `
  -c:a libmp3lame -b:a 96k `
  -content_type audio/mpeg `
  -f mp3 `
  icecast://source:indigo-source@127.0.0.1:8001/live.mp3
```

That creates a real live stream at:

```text
http://127.0.0.1:8001/live.mp3
```

Optional if your mic is quiet:

```powershell
& "C:\ProgramData\chocolatey\bin\ffmpeg.exe" `
  -f dshow -i audio="YOUR_MIC_NAME" `
  -filter:a "volume=2.0" `
  -ac 1 -ar 44100 `
  -c:a libmp3lame -b:a 96k `
  -content_type audio/mpeg `
  -f mp3 `
  icecast://source:indigo-source@127.0.0.1:8001/live.mp3
```

#### 3. Point Indigo at the live stream

For the Android emulator, use `10.0.2.2` so the emulator can reach the Windows host:

```bash
cd api
source venv/bin/activate
INDIGO_STREAM_URL=http://10.0.2.2:8001/live.mp3 \
INDIGO_STREAM_FORMAT=mp3 \
INDIGO_STREAM_IS_LIVE=true \
INDIGO_STREAM_RECOMMENDED_OFFSET_MS=0 \
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

If you are testing on a physical Android device instead of the emulator:
- Replace `10.0.2.2` with your Windows machine's LAN IP
- Example: `http://192.168.1.100:8001/live.mp3`

#### 4. Test end-to-end

1. Start Icecast on Windows
2. Start the `ffmpeg` mic publish command on Windows
3. Start the Indigo backend with `INDIGO_STREAM_URL=http://10.0.2.2:8001/live.mp3`
4. Start the Android emulator
5. Run the Android app
6. Open a game
7. Turn commentary ON
8. Speak into your mic
9. Confirm you hear your live voice in the app

Quick checks:
- In a Windows browser, open `http://127.0.0.1:8001/live.mp3`
- In the emulator, commentary should connect and show Playing or Buffering then Playing
- If the app plays your voice with a slight delay, that is expected for a live encoded stream

#### Live Commentary Troubleshooting

- Port `8001` already in use:
  - Change the Icecast `<port>` to another port like `8010`
  - Then update `INDIGO_STREAM_URL` to match
- `ffmpeg` does not show your mic:
  - Run the device list command again
  - Check Windows microphone privacy settings
  - Check that the mic works in another app first
- `ffmpeg` says it cannot connect to Icecast:
  - Make sure Icecast is already running
  - Recheck the source password in both `icecast.xml` and the `icecast://source:...` URL
- Browser cannot open `http://127.0.0.1:8001/live.mp3`:
  - The encoder is not publishing yet, or Icecast is not running
- Emulator cannot hear the stream:
  - Make sure `INDIGO_STREAM_URL` uses `http://10.0.2.2:8001/live.mp3`, not `localhost`
- Physical device cannot hear the stream:
  - Use your Windows LAN IP instead of `10.0.2.2`
  - Ensure Windows Firewall allows inbound connections to the Icecast port
- Buffering or too much delay:
  - Start with `96k` MP3 as shown above
  - Lower to `64k` if needed
  - Keep `INDIGO_STREAM_RECOMMENDED_OFFSET_MS=0` first, then adjust in-app sync manually

---

## Android App

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Setup

1. Open Android Studio
2. Open project: select `android/` directory
3. Let Gradle sync complete
4. Connect a device or start an emulator (API 26+)

### Run

1. Select `app` run configuration
2. Click Run (or `Shift+F10`)
3. App launches on device/emulator

### Configure Backend URL

By default the app points to `http://10.0.2.2:8000` (Android emulator localhost alias).

To change:
- Edit `android/app/src/main/java/com/indigo/app/data/api/ApiClient.kt`
- Update `BASE_URL` to your backend address

For physical device testing, use your machine's local IP (e.g., `http://192.168.1.100:8000`).

### Build APK

```bash
cd android
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

---

## Testing the Full Flow

1. Start the backend: `uvicorn app.main:app --reload --host 0.0.0.0 --port 8000`
2. Start the Android emulator
3. Run the app from Android Studio
4. You should see a list of live games
5. Tap a game → see detail screen with commentary controls
6. Turn commentary ON → audio should start playing
7. Adjust sync → offset changes and player seeks
8. Background the app → audio continues
9. Return to app → state is preserved

### Audio Reliability Notes

- The Android player retries playback automatically on transient ExoPlayer errors.
- If the test MP3 reaches the end, the app restarts it to simulate a continuous feed.
- Playback state is shown in the UI as Playing, Paused, Buffering, or Error.
- If playback enters Error, use the in-app Retry button first, then check Logcat.

---

## Troubleshooting

| Problem | Solution |
|---|---|
| App can't reach backend | Check `BASE_URL` in ApiClient.kt. For emulator use `10.0.2.2`. For device use machine IP. Ensure backend is running on `0.0.0.0`. |
| No audio playing | Open `http://localhost:8000/streams/test` in a browser to confirm the backend can serve the local file. Then check Logcat for `AudioPlayerManager` errors and use the in-app Retry button. |
| Gradle sync fails | Ensure JDK 17 is set in Android Studio. Check internet connection for dependency downloads. |
| Backend won't start | Check Python version (`python --version`). Use Python 3.12 for this repo. The pinned `pydantic==2.6.1` stack does not support Python 3.14. Ensure venv is activated, then run `pip install -r requirements.txt` again. |
| Background audio stops | Check battery optimization settings for the app. Some OEMs kill background services aggressively. |
| Sync feels wrong | Sync is manual in v1. Adjust by ear using -1s/+1s buttons. Larger offsets use -5s/+5s. |
