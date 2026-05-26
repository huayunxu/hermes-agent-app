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
import com.hermes.agent.data.VoiceCallState
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

    init {
        // Check voice capabilities once at startup
        checkVoiceCapabilities()
    }

    private fun checkVoiceCapabilities() {
        val sttAvailable = voiceController.isSpeechRecognizerAvailable()
        mutableState.update {
            it.copy(
                isSttAvailable = sttAvailable
            )
        }
        if (!sttAvailable) {
            // Pre-warn about STT unavailability in initial message
            mutableState.update {
                it.copy(
                    messages = listOf(
                        ChatMessage(
                            speaker = Speaker.Assistant,
                            text = "你好，我是 Hermes Agent。你的设备不支持本地语音识别，语音通话将录制音频并发送。如需语音交互，请使用语音消息模式或文字输入。"
                        )
                    )
                )
            }
        }
    }

    fun connect(baseUrl: String, accessToken: String) {
        val cleanUrl = baseUrl.trim().removeSuffix("/")
        val cleanToken = accessToken.trim()
        if (cleanToken.isBlank()) {
            mutableState.update { it.copy(error = "请填写访问令牌。") }
            return
        }
        if (cleanUrl.isBlank()) {
            mutableState.update { it.copy(error = "请填写 Hermes 服务地址。") }
            return
        }

        val session = HermesSession(baseUrl = cleanUrl, accessToken = cleanToken)
        sessionStore.save(session)
        // baseUrl from login is stored for display; HttpHermesAgentService hardcodes gateway:9999
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
        // Stop any ongoing voice activity when switching
        stopVoiceTurn()

        // Check STT availability for VoiceCall and VoiceMessage modes
        if (mode == ConversationMode.VoiceCall || mode == ConversationMode.VoiceMessage) {
            if (!voiceController.isSpeechRecognizerAvailable()) {
                mutableState.update {
                    it.copy(
                        mode = mode,
                        error = "当前设备不支持系统语音识别。语音消息功能需要 Google 语音服务。"
                    )
                }
                return
            }
        }

        mutableState.update { it.copy(mode = mode, error = null) }

        viewModelScope.launch {
            when (mode) {
                ConversationMode.Text -> Unit
                ConversationMode.VoiceMessage -> Unit
                // VoiceCall and Video use stubs for session management
                // Actual voice/video interaction happens through Composer + voiceController
                ConversationMode.VoiceCall -> {
                    // Add a hint message when entering VoiceCall
                    mutableState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                speaker = Speaker.System,
                                text = "语音通话模式：点击麦克风开始说话，收到回复后会自动播放。"
                            )
                        )
                    }
                }
                ConversationMode.Video -> {
                    mutableState.update {
                        it.copy(
                            messages = it.messages + ChatMessage(
                                speaker = Speaker.System,
                                text = "视频模式：摄像头画面实时传给 Hermes。输入文字描述你想让它注意的内容。"
                            )
                        )
                    }
                }
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
        // VoiceCall mode uses VoiceMessage's voice flow but with auto-TTS
        val speakReply = mutableState.value.mode == ConversationMode.VoiceMessage ||
                mutableState.value.mode == ConversationMode.VoiceCall

        if (!voiceController.isSpeechRecognizerAvailable()) {
            mutableState.update {
                it.copy(
                    isListening = false,
                    voiceState = VoiceCallState.Idle,
                    error = "当前设备不支持系统语音识别"
                )
            }
            return
        }

        mutableState.update { it.copy(isListening = true, voiceState = VoiceCallState.Recording, error = null) }
        voiceController.startListening(
            onResult = { transcript ->
                mutableState.update { it.copy(isListening = false, voiceState = VoiceCallState.Processing, input = transcript) }
                sendText(transcript, speakReply = speakReply)
            },
            onError = { message ->
                mutableState.update { it.copy(isListening = false, voiceState = VoiceCallState.Idle, error = message) }
            }
        )
    }

    fun stopVoiceTurn() {
        voiceController.stopListening()
        voiceController.stopSpeaking()
        mutableState.update { it.copy(isListening = false, voiceState = VoiceCallState.Idle) }
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
                voiceState = VoiceCallState.Processing,
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
                        voiceState = if (speakReply && reply.shouldSpeak) VoiceCallState.Speaking else VoiceCallState.Idle,
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
                        voiceState = VoiceCallState.Idle,
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