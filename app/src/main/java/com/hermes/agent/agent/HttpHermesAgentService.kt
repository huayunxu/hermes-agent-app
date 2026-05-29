package com.hermes.agent.agent

import com.hermes.agent.data.AgentReply
import com.hermes.agent.data.ApprovalRequest
import com.hermes.agent.data.ChatMessage
import com.hermes.agent.data.HermesSession
import com.hermes.agent.data.Speaker
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Hermes Agent HTTP client aligned with Hermes Web UI + Gateway (api_server).
 *
 * Routing (matches [Hermes Web UI proxy](packages/server proxy-handler)):
 * - **API Key set** → direct Gateway at port [API_SERVER_PORT] with `API_SERVER_KEY`.
 * - **API Key empty** → Web UI proxy `{webUi}/api/hermes/v1/...` with login JWT;
 *   the server injects `API_SERVER_KEY` when forwarding to Gateway.
 *
 * Chat: OpenAI-compatible `POST /v1/chat/completions` with `{ model, messages, stream: false }`.
 */
class HttpHermesAgentService(
    session: HermesSession
) : HermesAgentService {

    companion object {
        const val API_SERVER_PORT = 8642
        private const val CHAT_PATH = "/v1/chat/completions"
        private const val AUDIO_TRANSCRIBE_PATH = "/v1/audio/transcriptions"
        private const val WEB_UI_PROXY_PREFIX = "/api/hermes"
        private const val MAX_HISTORY_MESSAGES = 40
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 90_000
        private const val AUDIO_UPLOAD_TIMEOUT = 60_000
    }

    private val webUiBaseUrl: String = session.displayUrl.trimEnd('/')
    private val useWebUiProxy: Boolean = session.apiKey.isBlank()
    private val bearerToken: String =
        if (useWebUiProxy) session.accessToken else session.apiKey.ifBlank { session.accessToken }

    private val chatEndpoint: String
    private val audioTranscribeEndpoint: String

    private var activeModel: String = "default"

    init {
        if (useWebUiProxy) {
            chatEndpoint = "$webUiBaseUrl$WEB_UI_PROXY_PREFIX$CHAT_PATH"
            audioTranscribeEndpoint = "$webUiBaseUrl$WEB_UI_PROXY_PREFIX$AUDIO_TRANSCRIBE_PATH"
        } else {
            val gatewayBase = deriveGatewayBaseUrl(session.baseUrl)
            chatEndpoint = "$gatewayBase$CHAT_PATH"
            audioTranscribeEndpoint = "$gatewayBase$AUDIO_TRANSCRIBE_PATH"
        }
    }

    override suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply {
        activeModel = model
        return postChat(text, history, model)
    }

    override suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply {
        // Hermes Agent tool approvals use Socket.IO on Web UI (/chat-run), not HTTP.
        // Keep a clear reply until realtime bridge is wired on Android.
        return AgentReply(
            text = if (approved) {
                "已记录批准（$approvalId）。完整工具审批需通过 Hermes Web UI 的实时会话处理。"
            } else {
                "已记录拒绝（$approvalId）。"
            },
            shouldSpeak = false,
            approvalRequest = null
        )
    }

    override suspend fun setModel(model: String) {
        activeModel = model
    }

    override suspend fun startVoiceSession() = Unit
    override suspend fun startVideoSession() = Unit

    override suspend fun isAudioTranscriptionAvailable(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL(audioTranscribeEndpoint)
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "OPTIONS"
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                applyAuthHeaders(conn)
                val code = conn.responseCode
                conn.disconnect()
                code != HttpURLConnection.HTTP_NOT_FOUND
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun transcribeAudio(audioData: ByteArray, sampleRate: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                uploadAudioForTranscription(audioData, sampleRate)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun uploadAudioForTranscription(audioData: ByteArray, sampleRate: Int): String? {
        val boundary = "----HermesAudioBoundary${System.currentTimeMillis()}"
        val url = URL(audioTranscribeEndpoint)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = AUDIO_UPLOAD_TIMEOUT
        conn.readTimeout = AUDIO_UPLOAD_TIMEOUT
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.setRequestProperty("Accept", "application/json")
        applyAuthHeaders(conn)

        conn.outputStream.use { output ->
            output.write(buildMultipartBody(audioData, boundary))
        }

        val responseCode = conn.responseCode
        val responseBody = readResponseBody(conn, responseCode)
        conn.disconnect()

        return if (responseCode in 200..299) {
            parseTranscriptionResponse(responseBody)
        } else {
            null
        }
    }

    private fun buildMultipartBody(audioData: ByteArray, boundary: String): ByteArray {
        val builder = StringBuilder()
        builder.append("--$boundary\r\n")
        builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"recording.wav\"\r\n")
        builder.append("Content-Type: audio/wav\r\n\r\n")
        val headerBytes = builder.toString().toByteArray(Charsets.UTF_8)
        val footerBytes = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        return headerBytes + audioData + footerBytes
    }

    private fun parseTranscriptionResponse(body: String): String? {
        return try {
            val json = JSONObject(body)
            json.optString("text").takeIf { it.isNotBlank() }
                ?: json.optString("transcription").takeIf { it.isNotBlank() }
                ?: json.optJSONArray("results")?.optJSONObject(0)?.optString("transcripts")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun postChat(text: String, history: List<ChatMessage>, model: String): AgentReply {
        return withContext(Dispatchers.IO) {
            val messages = buildMessages(history, text)

            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)

            val body = httpPost(chatEndpoint, payload)

            if (!body.startsWith("{")) {
                return@withContext AgentReply(text = body)
            }

            val json = JSONObject(body)
            parseApproval(json)?.let { approval ->
                val hint = json.optString("text").ifBlank {
                    json.optString("message").ifBlank { "此操作需要你确认后才能继续。" }
                }
                return@withContext AgentReply(
                    text = hint,
                    shouldSpeak = false,
                    approvalRequest = approval
                )
            }

            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                val fallback = json.optString("text").ifBlank { json.optString("error", body) }
                return@withContext AgentReply(text = fallback)
            }

            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            val content = message?.optString("content")?.takeIf { it.isNotBlank() }
                ?: choice.optString("text", "")

            val approvalInMessage = message?.let { parseApproval(it) }
            AgentReply(
                text = content.ifBlank { body },
                shouldSpeak = true,
                approvalRequest = approvalInMessage
            )
        }
    }

    /**
     * Build OpenAI-style messages. [history] must not include the current user turn;
     * [newText] is appended once as the latest user message.
     */
    private fun buildMessages(history: List<ChatMessage>, newText: String): JSONArray {
        val messages = JSONArray()
        history
            .filter { it.speaker == Speaker.User || it.speaker == Speaker.Assistant }
            .takeLast(MAX_HISTORY_MESSAGES)
            .forEach { msg ->
                messages.put(
                    JSONObject()
                        .put("role", if (msg.speaker == Speaker.User) "user" else "assistant")
                        .put("content", msg.text)
                )
            }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", newText)
        )
        return messages
    }

    private fun parseApproval(json: JSONObject): ApprovalRequest? {
        val approval = json.optJSONObject("approval")
            ?: json.optJSONObject("approvalRequest")
            ?: return null
        val id = approval.optString("id").ifBlank { approval.optString("approval_id") }
        if (id.isBlank()) return null
        val title = approval.optString("title").ifBlank { "确认执行 Hermes 操作" }
        val description = approval.optString("description").ifBlank { approval.optString("command", "") }
        return ApprovalRequest(id = id, title = title, description = description)
    }

    private fun httpPost(urlStr: String, payload: JSONObject): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT
        conn.readTimeout = READ_TIMEOUT
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        applyAuthHeaders(conn)

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val responseCode = conn.responseCode
        val responseBody = readResponseBody(conn, responseCode)
        conn.disconnect()

        if (responseCode !in 200..299) {
            val hint = when {
                responseCode == 401 && useWebUiProxy ->
                    "认证失败：请重新登录 Hermes Web UI，或填写 API Key（来自 ~/.hermes API_SERVER_KEY）。"
                responseCode == 401 ->
                    "Gateway 认证失败：请检查登录页中的 API Key 是否与服务器 API_SERVER_KEY 一致。"
                else -> "Hermes 返回 $responseCode"
            }
            error("$hint：${responseBody.take(200)}")
        }

        return responseBody
    }

    private fun applyAuthHeaders(conn: HttpURLConnection) {
        if (bearerToken.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $bearerToken")
        }
    }

    private fun readResponseBody(conn: HttpURLConnection, responseCode: Int): String {
        return if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }
    }

    private fun deriveGatewayBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        if (trimmed.contains(":$API_SERVER_PORT")) {
            return trimmed
        }
        val url = try {
            URL(trimmed)
        } catch (_: Exception) {
            return "$trimmed:$API_SERVER_PORT"
        }
        return "${url.protocol}://${url.host}:$API_SERVER_PORT"
    }
}
