# Hermes Agent Android

Hermes Agent Android is a native Kotlin prototype for a Doubao-style AI companion app. It includes:

- Text chat with streaming-ready state management
- Voice message mode with Android speech recognition and text-to-speech
- Voice call entry point for future realtime sessions
- Video mode entry point for future realtime sessions
- Slash command support, including `/model <default|fast|smart|vision>`
- Human approval cards for tool-style operations that require confirmation
- A replaceable `HermesAgentService` boundary for connecting the real Hermes backend

## Recommended Product Shape

For Hermes Agent, a native Android app is the right first client if voice and camera latency matter. A better long-term plan is:

1. Native Android MVP for high-quality voice/video interaction.
2. Shared Hermes realtime backend for text, speech, vision, memory, and tool calls.
3. Later add iOS and web clients against the same backend APIs.

## Project Structure

```text
app/src/main/java/com/hermes/agent/
  MainActivity.kt
  agent/                  Agent service boundary and local prototype service
  camera/                 Video surface placeholder
  data/                   UI/domain models
  ui/                     Compose screens and theme
  viewmodel/              Conversation state
  voice/                  Speech recognition and TTS controller
```

## Connect Hermes Agent / Web UI

The login screen matches **Hermes Web UI** (`POST /api/auth/login` → `{ "token": "..." }`):

| Field | Purpose |
|-------|---------|
| LAN / WAN URL | Web UI base URL (e.g. `http://10.1.1.50:80`) |
| Username / Password | Same as Web UI login |
| API Key (optional) | `API_SERVER_KEY` from `~/.hermes/.env` on the server |

**Chat routing** (`HttpHermesAgentService`):

1. **API Key provided** → direct Gateway `POST http://{host}:8642/v1/chat/completions` with `Authorization: Bearer {API_SERVER_KEY}`.
2. **API Key empty** → Web UI proxy `POST {webUi}/api/hermes/v1/chat/completions` with login JWT; the server injects `API_SERVER_KEY` when forwarding (same as the browser).

OpenAI-style body: `{ "model", "messages", "stream": false }`.

**Voice**: device STT when available; otherwise raw WAV upload to `/v1/audio/transcriptions` on the same route (direct or proxied).

**Tool approvals** on Web UI use Socket.IO (`approval.requested` / `approval.respond`), not HTTP. The Android app can display approval cards if the Gateway returns an `approval` object in JSON; full realtime approval needs a future Socket.IO client.

Credentials are stored in app-private SharedPreferences for the MVP. Use Android Keystore before production.

Keep the UI and `HermesViewModel` unchanged by preserving `HermesAgentService`.

## Build

Open this folder in Android Studio and sync Gradle, then run the `app` configuration.

## GitHub Build

The project includes `.github/workflows/android.yml`. After pushing this repo to GitHub, run the `Android APK` workflow manually or push to `main`/`master`.

The workflow installs JDK 17, Android SDK 35, Gradle 8.10.2, builds `assembleDebug`, and uploads the debug APK as a workflow artifact named `hermes-agent-debug-apk`.
