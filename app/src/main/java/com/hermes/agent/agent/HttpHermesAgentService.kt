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

class HttpHermesAgentService(
    private val session: HermesSession
) : HermesAgentService {
    private var activeModel: String = "default"

    override suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply {
        activeModel = model
        return postJson(
            path = "chat-run",
            payload = buildPayload(history, text, model)
        )
    }

    override suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply {
        return postJson(
            path = "approval",
            payload = JSONObject()
                .put("approvalId", approvalId)
                .put("approved", approved)
                .put("model", activeModel)
        )
    }

    override suspend fun setModel(model: String) {
        activeModel = model
    }

    override suspend fun startVoiceSession() = Unit

    override suspend fun startVideoSession() = Unit

    private suspend fun postJson(path: String, payload: JSONObject): AgentReply {
        return withContext(Dispatchers.IO) {
            val endpoint = URL("${session.baseUrl.removeSuffix("/")}/${path.trimStart('/')}")
            val connection = endpoint.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json, text/plain")
            connection.setRequestProperty("Authorization", "Bearer ${session.accessToken}")

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = connection.responseCode
            val body = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            }
            connection.disconnect()

            if (responseCode !in 200..299) {
                error("Hermes 服务返回 $responseCode：${body.take(160)}")
            }

            parseReply(body)
        }
    }

    private fun buildPayload(history: List<ChatMessage>, text: String, model: String): JSONObject {
        val messages = JSONArray()
        history.forEach { message ->
            messages.put(
                JSONObject()
                    .put(
                        "role",
                        when (message.speaker) {
                            Speaker.User -> "user"
                            Speaker.Assistant -> "assistant"
                            Speaker.System -> "system"
                        }
                    )
                    .put("content", message.text)
            )
        }

        return JSONObject()
            .put("text", text)
            .put("message", text)
            .put("model", model)
            .put("messages", messages)
    }

    private fun parseReply(body: String): AgentReply {
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return AgentReply(text = trimmed)

        val json = JSONObject(trimmed)
        val text = listOf("text", "answer", "content", "message", "response")
            .firstNotNullOfOrNull { key -> json.optString(key).takeIf { it.isNotBlank() } }
            ?: trimmed
        return AgentReply(text = text, approvalRequest = parseApproval(json))
    }

    private fun parseApproval(json: JSONObject): ApprovalRequest? {
        val approval = json.optJSONObject("approval")
            ?: json.optJSONObject("approvalRequest")
            ?: return null
        val id = approval.optString("id").takeIf { it.isNotBlank() } ?: return null
        return ApprovalRequest(
            id = id,
            title = approval.optString("title").ifBlank { "需要确认操作" },
            description = approval.optString("description").ifBlank { "Hermes 请求你批准后继续执行。" }
        )
    }
}
