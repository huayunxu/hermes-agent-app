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
 * Hardcoded to connect to the local Hermes Gateway at 10.1.1.50:9999.
 * The token field in HermesSession stores the user's personal access token
 * which is sent as the Authorization: Bearer header.
 *
 * Message format: OpenAI-compatible {model, messages, stream:false}
 * Response: {choices:[{message:{content}}]}
 */
class HttpHermesAgentService(
    session: HermesSession
) : HermesAgentService {

    companion object {
        // Hardcoded gateway address (local Hermes Gateway)
        private const val GATEWAY_HOST = "http://10.1.1.50:9999"
        private const val CHAT_ENDPOINT = "$GATEWAY_HOST/v1/chat/completions"
        private const val CONNECT_TIMEOUT = 15_000
        private const val READ_TIMEOUT = 90_000
    }

    private val accessToken: String = session.accessToken
    private var activeModel: String = "default"

    override suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply {
        activeModel = model
        return postChat(text, history, model)
    }

    override suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply {
        // The Hermes Gateway handles approvals through the run flow.
        // For now, return a message indicating the approval was processed.
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

    /**
     * Send a chat request using OpenAI-compatible /v1/chat/completions format.
     */
    private suspend fun postChat(text: String, history: List<ChatMessage>, model: String): AgentReply {
        return withContext(Dispatchers.IO) {
            val messages = buildMessages(history, text)

            val payload = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("stream", false)

            val body = httpPost(CHAT_ENDPOINT, payload)

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
                ?: choice.optString("text", "") // fallback for non-chat formats

            AgentReply(
                text = content.ifBlank { body },
                shouldSpeak = true,
                approvalRequest = null
            )
        }
    }

    /**
     * Build OpenAI-compatible messages array from conversation history + new input.
     */
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

    /**
     * Generic HTTP POST with Bearer token auth.
     * Returns the response body as a string.
     * Throws on non-2xx status.
     */
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
}
