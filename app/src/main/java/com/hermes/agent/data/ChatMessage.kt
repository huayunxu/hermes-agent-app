package com.hermes.agent.data

import java.time.Instant
import java.util.UUID

enum class Speaker {
    User,
    Assistant,
    System
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val speaker: Speaker,
    val text: String,
    val createdAt: Instant = Instant.now()
)

data class AgentReply(
    val text: String,
    val shouldSpeak: Boolean = true,
    val approvalRequest: ApprovalRequest? = null
)

data class ApprovalRequest(
    val id: String,
    val title: String,
    val description: String
)

data class HermesSession(
    val baseUrl: String,
    val accessToken: String
) {
    val displayUrl: String
        get() = baseUrl.removeSuffix("/")
}

enum class ConversationMode {
    Text,
    VoiceMessage,
    VoiceCall,
    Video
}

data class HermesUiState(
    val session: HermesSession? = null,
    val mode: ConversationMode = ConversationMode.Text,
    val input: String = "",
    val selectedModel: String = "default",
    val availableModels: List<String> = listOf("default", "fast", "smart", "vision"),
    val pendingApproval: ApprovalRequest? = null,
    val messages: List<ChatMessage> = listOf(
        ChatMessage(
            speaker = Speaker.Assistant,
            text = "你好，我是 Hermes Agent。你可以打字、发送语音消息、进入语音通话，也可以使用 /model 切换模型。"
        )
    ),
    val isThinking: Boolean = false,
    val isListening: Boolean = false,
    val error: String? = null
)
