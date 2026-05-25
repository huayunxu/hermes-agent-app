# Hermes Agent Android

Hermes Agent Android is a native Kotlin prototype for a Doubao-style AI companion app. It includes:

- Text chat with streaming-ready state management
- Voice message mode with Android speech recognition and text-to-speech
- Voice call entry point for future realtime sessions
- Video mode with camera preview scaffolding
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
  camera/                 Camera preview component
  data/                   UI/domain models
  ui/                     Compose screens and theme
  viewmodel/              Conversation state
  voice/                  Speech recognition and TTS controller
```

## Connect A Real Hermes Backend

The app now starts with a connection screen. Use:

- Hermes service URL, for example `https://your-hermes.example.com/api`
- Hermes access token, copied from your Hermes backend or Web UI admin/session tooling

The token is stored in app-private Android shared preferences for the MVP. Move this to Android Keystore-backed encrypted storage before using production tokens.

`HttpHermesAgentService` sends chat requests to:

```text
POST {baseUrl}/chat-run
Authorization: Bearer {accessToken}
```

The payload includes `text`, `message`, `model`, and OpenAI-style `messages`.
Approval decisions are sent to:

```text
POST {baseUrl}/approval
Authorization: Bearer {accessToken}
```

The app understands approval responses shaped like:

```json
{
  "text": "This action requires approval.",
  "approval": {
    "id": "approval-id",
    "title": "Approve tool call",
    "description": "Hermes wants to run an external operation."
  }
}
```

If your Hermes endpoint uses a different path or payload, update only `HttpHermesAgentService`.

Keep the UI and `HermesViewModel` unchanged by preserving:

```kotlin
interface HermesAgentService {
    suspend fun sendText(history: List<ChatMessage>, text: String): AgentReply
    suspend fun startVoiceSession()
    suspend fun startVideoSession()
}
```

For realtime voice/video, use a WebRTC or WebSocket session owned by the service layer, then surface transcripts and assistant events back into `HermesViewModel`.

## Build

Open this folder in Android Studio and sync Gradle, then run the `app` configuration.

## GitHub Build

The project includes `.github/workflows/android.yml`. After pushing this repo to GitHub, run the `Android APK` workflow manually or push to `main`/`master`.

The workflow installs JDK 17, Android SDK 35, Gradle 8.10.2, builds `assembleDebug`, and uploads the debug APK as a workflow artifact named `hermes-agent-debug-apk`.
