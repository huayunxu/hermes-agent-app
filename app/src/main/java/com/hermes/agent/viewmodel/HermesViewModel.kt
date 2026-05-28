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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

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
        checkVoiceCapabilities()
    }

    private fun checkVoiceCapabilities() {
        val sttAvailable = voiceController.isSpeechRecognizerAvailable()
        mutableState.update {
            it.copy(isSttAvailable = sttAvailable)
        }
        if (!sttAvailable) {
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

    /**
     * Connect to Hermes using username + password login.
     *
     * Flow:
     * 1. Check reachability (LAN preferred, WAN fallback)
     * 2. Login via POST /api/auth/login on the web UI server to get a token
     * 3. Save session and switch to chat view
     */
    fun connect(lanUrl: String, wanUrl: String, username: String, password: String, apiKey: String = "") {
        val cleanLan = lanUrl.trim().removeSuffix("/")
        val cleanWan = wanUrl.trim().removeSuffix("/")
        val cleanUser = username.trim()
        val cleanPass = password.trim()
        val cleanApiKey = apiKey.trim()

        if (cleanLan.isBlank() && cleanWan.isBlank()) {
            mutableState.update { it.copy(error = "请填写至少一个服务地址。") }
            return
        }
        if (cleanUser.isBlank() || cleanPass.isBlank()) {
            mutableState.update { it.copy(error = "请填写用户名和密码。") }
            return
        }

        mutableState.update { it.copy(error = null, isThinking = true) }
        viewModelScope.launch {
            // Step 1: Find reachable server
            val selectedUrl = checkReachable(cleanLan, cleanWan)
            if (selectedUrl == null) {
                mutableState.update {
                    it.copy(
                        error = "所有地址均无法连接。请检查网络或地址是否正确。",
                        isThinking = false
                    )
                }
                return@launch
            }

            // Step 2: Login to get token
            val token = try {
                loginWithPassword(selectedUrl, cleanUser, cleanPass)
            } catch (e: Exception) {
                mutableState.update {
                    it.copy(
                        error = "登录失败：${e.message ?: "用户名或密码错误"}",
                        isThinking = false
                    )
                }
                return@launch
            }

            if (token.isNullOrBlank()) {
                mutableState.update {
                    it.copy(
                        error = "登录失败：未获取到访问令牌。",
                        isThinking = false
                    )
                }
                return@launch
            }

            // Step 3: Save session
            val session = HermesSession(
                lanUrl = cleanLan,
                wanUrl = cleanWan,
                selectedUrl = selectedUrl,
                accessToken = token,
                apiKey = cleanApiKey
            )
            sessionStore.save(session)
            agentService = HttpHermesAgentService(session)
            mutableState.update { it.copy(session = session, isThinking = false) }
        }
    }

    /**
     * Check if LAN or WAN address is reachable.
     * Tries /api/health and /health endpoints.
     */
    private suspend fun checkReachable(lan: String, wan: String): String? {
        return withContext(Dispatchers.IO) {
            // Try LAN first with shorter timeout
            if (lan.isNotBlank()) {
                for (path in listOf("/api/health", "/health")) {
                    try {
                        val url = URL("$lan$path")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.requestMethod = "GET"
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..399) return@withContext lan
                    } catch (_: Exception) { }
                }
            }
            // Fallback to WAN with longer timeout
            if (wan.isNotBlank()) {
                for (path in listOf("/api/health", "/health")) {
                    try {
                        val url = URL("$wan$path")
                        val conn = url.openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.requestMethod = "GET"
                        val code = conn.responseCode
                        conn.disconnect()
                        if (code in 200..399) return@withContext wan
                    } catch (_: Exception) { }
                }
            }
            null
        }
    }

    /**
     * Login via POST /api/auth/login to get a bearer token.
     * This is the same endpoint the Capacitor web UI uses.
     */
    private suspend fun loginWithPassword(baseUrl: String, username: String, password: String): String? {
        return withContext(Dispatchers.IO) {
            val loginUrl = URL("$baseUrl/api/auth/login")
            val conn = loginUrl.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")

            val payload = JSONObject()
                .put("username", username)
                .put("password", password)

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val responseCode = conn.responseCode
            val responseBody = if (responseCode in 200..299) {
                conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                val errorBody = conn.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                // Try to extract error message from response
                val errorMsg = try {
                    JSONObject(errorBody).optString("error", "")
                } catch (e: Exception) { "" }
                conn.disconnect()
                if (errorMsg.isNotBlank()) {
                    throw Exception(errorMsg)
                } else {
                    when (responseCode) {
                        401 -> throw Exception("用户名或密码错误")
                        404 -> throw Exception("服务器不支持密码登录（/api/auth/login 不存在）")
                        else -> throw Exception("登录请求失败 (HTTP $responseCode)")
                    }
                }
            }
            conn.disconnect()

            // Parse token from response
            try {
                val json = JSONObject(responseBody)
                json.optString("token").takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun disconnect() {
        sessionStore.clear()
        mutableState.update { HermesUiState() }
    }

    fun setInput(input: String) {
        mutableState.update { it.copy(input = input, error = null) }
    }

    fun switchMode(mode: ConversationMode) {
        stopVoiceTurn()

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
                ConversationMode.VoiceCall -> {
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
