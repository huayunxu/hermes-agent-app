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
 * Hermes Gateway API client (OpenAI-compatible /v1/chat/completions).
 *
 * Connects to the Hermes Gateway api_server platform at the URL derived
 * from the user's configured server address. The api_server runs on port
 * 8642 by default. If the user enters a URL with a different port (e.g.
 * the web UI on port 80), the client replaces the port with 8642.
 *
 * The token field in HermesSession stores the user's personal access token
 * which is sent as the Authorization: Bearer ***
 *
 * Message format: OpenAI-compatible {model, messages, stream:false}
 * Response: {choices:[{message:{content}}]}
 */
class HttpHermesAgentService(
    session: HermesSession
) : HermesAgentService {

    companion object {
        /** Default api_server port */
        const val API_SERVER_PORT = 8642
        private const val CHAT_ENDPOINT_PATH = "/v1/chat/completions"
        private const val AUDIO_TRANSCRIBE_ENDPOINT_PATH = "/v1/audio/transcriptions"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 90_000
        private const val AUDIO_UPLOAD_TIMEOUT = 60_000
    }

    private val apiBaseUrl: String = deriveApiBaseUrl(session.baseUrl)
    private val chatEndpoint = "$apiBaseUrl$CHAT_ENDPOINT_PATH"
    private val audioTranscribeEndpoint = "$apiBaseUrl$AUDIO_TRANSCRIBE_ENDPOINT_PATH"
    private val accessToken: String = session.accessToken
    private var activeModel: String = "default"

    override suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply {
        activeModel = model
        return postChat(text, history, model)
    }

    override suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply {
        return AgentReply(
            text = "Approval (id=$approvalId, approved=$approved) submitted.",
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
                conn.requestMethod = "POST"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty("Authorization", "Bearer $accessToken")
                conn.disconnect()
                true
            } catch (e: Exception) {
                false
            }
        }
    }

    override suspend fun transcribeAudio(audioData: ByteArray, sampleRate: Int): String? {
        return withContext(Dispatchers.IO) {
            try {
                uploadAudioForTranscription(audioData, sampleRate)
            } catch (e: Exception) {
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
        conn.setRequestProperty("Authorization", "Bearer $accessToken")
        conn.setRequestProperty("Accept", "application/json")

        conn.outputStream.use { output ->
            val body = buildMultipartBody(audioData, sampleRate, boundary)
            output.write(body)
        }

        val responseCode = conn.responseCode
        return if (responseCode in 200..299) {
            val responseBody = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseTranscriptionResponse(responseBody)
        } else {
            null
        }
    }

    private fun buildMultipartBody(audioData: ByteArray, sampleRate: Int, boundary: String): ByteArray {
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
        } catch (e: Exception) {
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
            val choices = json.optJSONArray("choices") ?: return@withContext AgentReply(text = body)

            if (choices.length() == 0) {
                return@withContext AgentReply(text = body)
            }

            val choice = choices.getJSONObject(0)
            val message = choice.optJSONObject("message")
            val content = message?.optString("content")?.takeIf { it.isNotBlank() }
                ?: choice.optString("text", "")

            AgentReply(
                text = content.ifBlank { body },
                shouldSpeak = true,
                approvalRequest = null
            )
        }
    }

    private fun buildMessages(history: List<ChatMessage>, newText: String): JSONArray {
        val messages = JSONArray()
        history.forEach { msg ->
            messages.put(JSONObject()
                .put("role", when (msg.speaker) {
                    Speaker.User -> "user"
                    Speaker.Assistant -> "assistant"
                    Speaker.System -> "system"
                })
                .put("content", msg.text))
        }
        messages.put(JSONObject()
            .put("role", "user")
            .put("content", newText))
        return messages
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
        conn.setRequestProperty("Authorization", "Bearer $accessToken")

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(payload.toString())
        }

        val responseCode = conn.responseCode
        val responseBody = if (responseCode in 200..299) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } else {
            conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        }
        conn.disconnect()

        if (responseCode !in 200..299) {
            error("Hermes Gateway 返回 $responseCode：${responseBody.take(200)}")
        }

        return responseBody
    }

    /**
     * Derive the API base URL from the user-provided server address.
     *
     * The user enters the web UI address (e.g. http://10.1.1.50:80).
     * The api_server runs on a separate port (default 8642).
     *
     * Strategy:
     * - If the URL already contains port 8642, use it as-is
     * - Otherwise, replace the port with 8642
     * - If no port in URL, append :8642
     */
    private fun deriveApiBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')

        // If already pointing at api_server port, use as-is
        if (trimmed.contains(":$API_SERVER_PORT")) {
            return trimmed
        }

        // Try to replace the port in the URL
        val url = try { URL(trimmed) } catch (e: Exception) { return "$trimmed:$API_SERVER_PORT" }

        val newPort = API_SERVER_PORT
        return "${url.protocol}://${url.host}:$newPort"
    }
}
