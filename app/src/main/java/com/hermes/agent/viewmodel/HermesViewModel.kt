package com.hermes.agent.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hermes.agent.agent.HttpHermesAgentService
import com.hermes.agent.agent.HermesAgentService
import com.hermes.agent.auth.HermesSessionStore
import com.hermes.agent.data.ChatMessage
import com.hermes.agent.data.ConversationMode
import com.hermes.agent.data.HermesSession
import com.hermes.agent.data.HermesUiState
import com.hermes.agent.data.Speaker
import com.hermes.agent.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HermesViewModel(
    agentService: HermesAgentService,
    private val voiceController: VoiceController,
    private val sessionStore: HermesSessionStore,
    initialSession: HermesSession?
) : ViewModel() {
    private var agentService: HermesAgentService =
        initialSession?.let { HttpHermesAgentService(it) } ?: agentService

    private val mutableState = MutableStateFlow(HermesUiState(session = initialSession))
    val state: StateFlow<HermesUiState> = mutableState

    fun connect(baseUrl: String, accessToken: String) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val cleanToken = accessToken.trim()
        if (cleanUrl.isBlank() || cleanToken.isBlank()) {
            mutableState.update { it.copy(error = "请填写 Hermes 服务地址和访问令牌。") }
            return
        }

        val session = HermesSession(baseUrl = cleanUrl, accessToken = cleanToken)
        sessionStore.save(session)
        agentService = HttpHermesAgentService(session)
        mutableState.update { it.copy(session = session, error = null) }
    }

    fun disconnect() {
        sessionStore.clear()
        mutableState.update { HermesUiState() }
    }

    fun setInput(input: String) {
        mutableState.update { it.copy(input = input, error = null) }
    }

    fun switchMode(mode: ConversationMode) {
        mutableState.update { it.copy(mode = mode, error = null) }
        viewModelScope.launch {
            when (mode) {
                ConversationMode.Text -> Unit
                ConversationMode.VoiceMessage -> Unit
                ConversationMode.VoiceCall -> agentService.startVoiceSession()
                ConversationMode.Video -> agentService.startVideoSession()
            }
        }
    }

    fun selectModel(model: String) {
        mutableState.update {
            it.copy(
                selectedModel = model,
                input = "",
                error = null,
                messages = it.messages + ChatMessage(
                    speaker = Speaker.System,
                    text = "已切换模型：$model"
                )
            )
        }
        viewModelScope.launch {
            runCatching { agentService.setModel(model) }
        }
    }

    fun sendCurrentInput() {
        val text = mutableState.value.input.trim()
        if (text.isBlank()) return
        if (handleSlashCommand(text)) return
        sendText(text, speakReply = mutableState.value.mode == ConversationMode.VoiceMessage)
    }

    fun startVoiceTurn() {
        mutableState.update { it.copy(isListening = true, error = null) }
        voiceController.startListening(
            onResult = { transcript ->
                mutableState.update { it.copy(isListening = false, input = transcript) }
                sendText(transcript, speakReply = true)
            },
            onError = { message ->
                mutableState.update { it.copy(isListening = false, error = message) }
            }
        )
    }

    fun stopVoiceTurn() {
        voiceController.stopListening()
        mutableState.update { it.copy(isListening = false) }
    }

    fun decideApproval(approved: Boolean) {
        val approval = mutableState.value.pendingApproval ?: return
        mutableState.update {
            it.copy(
                pendingApproval = null,
                messages = it.messages + ChatMessage(
                    speaker = Speaker.User,
                    text = if (approved) "已批准：${approval.title}" else "已拒绝：${approval.title}"
                ),
                isThinking = true
            )
        }
        viewModelScope.launch {
            runCatching {
                agentService.sendApproval(approval.id, approved)
            }.onSuccess { reply ->
                mutableState.update {
                    it.copy(
                        isThinking = false,
                        pendingApproval = reply.approvalRequest,
                        messages = it.messages + ChatMessage(
                            speaker = Speaker.Assistant,
                            text = reply.text
                        )
                    )
                }
            }.onFailure { throwable ->
                mutableState.update {
                    it.copy(
                        isThinking = false,
                        error = throwable.message ?: "审批结果提交失败。"
                    )
                }
            }
        }
    }

    private fun handleSlashCommand(text: String): Boolean {
        if (!text.startsWith("/")) return false
        val parts = text.drop(1).split(Regex("\\s+")).filter { it.isNotBlank() }
        when (parts.firstOrNull()) {
            "model" -> {
                val requestedModel = parts.getOrNull(1)
                if (requestedModel == null) {
                    mutableState.update {
                        it.copy(
                            input = "",
                            error = "请输入 /model fast、/model smart 或 /model vision。"
                        )
                    }
                } else if (requestedModel in mutableState.value.availableModels) {
                    selectModel(requestedModel)
                } else {
                    mutableState.update {
                        it.copy(
                            input = "",
                            error = "未知模型：$requestedModel"
                        )
                    }
                }
                return true
            }
            "help" -> {
                mutableState.update {
                    it.copy(
                        input = "",
                        messages = it.messages + ChatMessage(
                            speaker = Speaker.System,
                            text = "可用命令：/model <default|fast|smart|vision>，/help"
                        )
                    )
                }
                return true
            }
            else -> return false
        }
    }

    private fun sendText(text: String, speakReply: Boolean) {
        val userMessage = ChatMessage(speaker = Speaker.User, text = text)
        mutableState.update {
            it.copy(
                input = "",
                isThinking = true,
                error = null,
                messages = it.messages + userMessage
            )
        }

        viewModelScope.launch {
            runCatching {
                agentService.sendText(
                    history = mutableState.value.messages,
                    text = text,
                    model = mutableState.value.selectedModel
                )
            }.onSuccess { reply ->
                val assistantMessage = ChatMessage(
                    speaker = Speaker.Assistant,
                    text = reply.text
                )
                mutableState.update {
                    it.copy(
                        isThinking = false,
                        pendingApproval = reply.approvalRequest,
                        messages = it.messages + assistantMessage
                    )
                }
                if (speakReply && reply.shouldSpeak) {
                    voiceController.speak(reply.text)
                }
            }.onFailure { throwable ->
                mutableState.update {
                    it.copy(
                        isThinking = false,
                        error = throwable.message ?: "Hermes Agent 暂时不可用。"
                    )
                }
            }
        }
    }

    override fun onCleared() {
        voiceController.shutdown()
        super.onCleared()
    }
}
