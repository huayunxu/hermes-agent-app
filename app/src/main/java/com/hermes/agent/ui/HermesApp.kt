package com.hermes.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Textsms
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hermes.agent.camera.VideoPreview
import com.hermes.agent.data.ApprovalRequest
import com.hermes.agent.data.ChatMessage
import com.hermes.agent.data.ConversationMode
import com.hermes.agent.data.Speaker
import com.hermes.agent.data.VoiceCallState
import com.hermes.agent.viewmodel.HermesViewModel

@Composable
fun HermesApp(viewModel: HermesViewModel) {
    val state by viewModel.state.collectAsState()

    if (state.session == null) {
        LoginScreen(
            error = state.error,
            isConnecting = state.isThinking,
            onConnect = { lan, wan, user, pass -> viewModel.connect(lan, wan, user, pass) }
        )
        return
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                ModeItem(ConversationMode.Text, state.mode, "文字", viewModel::switchMode)
                ModeItem(ConversationMode.VoiceMessage, state.mode, "语音消息", viewModel::switchMode)
                ModeItem(ConversationMode.VoiceCall, state.mode, "语音通话", viewModel::switchMode)
                ModeItem(ConversationMode.Video, state.mode, "视频", viewModel::switchMode)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(paddingValues)
                .imePadding()
        ) {
            Header(
                mode = state.mode,
                serverUrl = state.session?.displayUrl.orEmpty(),
                onDisconnect = viewModel::disconnect
            )
            if (state.mode == ConversationMode.Video) {
                VideoPanel(modifier = Modifier.weight(0.85f))
            }
            Conversation(
                messages = state.messages,
                isThinking = state.isThinking,
                modifier = Modifier.weight(1f)
            )
            state.pendingApproval?.let { approval ->
                ApprovalCard(
                    approval = approval,
                    onApprove = { viewModel.decideApproval(true) },
                    onReject = { viewModel.decideApproval(false) }
                )
            }
            state.error?.let { ErrorStrip(message = it) }
            Composer(
                mode = state.mode,
                input = state.input,
                selectedModel = state.selectedModel,
                models = state.availableModels,
                isListening = state.isListening,
                voiceState = state.voiceState,
                onInput = viewModel::setInput,
                onSend = viewModel::sendCurrentInput,
                onModelSelect = viewModel::selectModel,
                onListen = viewModel::startVoiceTurn,
                onStopListen = viewModel::stopVoiceTurn
            )
        }
    }
}

@Composable
private fun LoginScreen(
    error: String?,
    isConnecting: Boolean,
    onConnect: (String, String, String, String) -> Unit
) {
    var lanUrl by rememberSaveable { mutableStateOf("") }
    var wanUrl by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Hermes Agent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "连接你的 Hermes 服务后，App 会保留文字沟通、语音消息、语音通话、视频入口和 / 命令能力。",
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(24.dp))
        OutlinedTextField(
            value = lanUrl,
            onValueChange = { lanUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("内网地址（优先）") },
            placeholder = { Text("例如：http://10.1.1.50:80") },
            singleLine = true,
            enabled = !isConnecting
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = wanUrl,
            onValueChange = { wanUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("外网地址（备用）") },
            placeholder = { Text("例如：https://your-domain.com") },
            singleLine = true,
            enabled = !isConnecting
        )
        Spacer(Modifier.size(12.dp))
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("用户名") },
            placeholder = { Text("登录 Hermes Web UI 的用户名") },
            singleLine = true,
            enabled = !isConnecting
        )
        Spacer(Modifier.size(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("密码") },
            placeholder = { Text("登录 Hermes Web UI 的密码") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !isConnecting
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = "自动检测：优先连接内网地址，不通则自动切换到外网地址。至少填写一个地址。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        error?.let {
            Spacer(Modifier.size(12.dp))
            ErrorStrip(message = it)
        }
        if (isConnecting) {
            Spacer(Modifier.size(12.dp))
            Text(
                text = "正在登录...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Spacer(Modifier.size(18.dp))
        Button(
            onClick = { onConnect(lanUrl, wanUrl, username, password) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isConnecting
        ) {
            Text(if (isConnecting) "连接中..." else "登录 Hermes")
        }
    }
}

@Composable
private fun Header(
    mode: ConversationMode,
    serverUrl: String,
    onDisconnect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Hermes Agent",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (mode) {
                    ConversationMode.Text -> "文字沟通和 / 命令"
                    ConversationMode.VoiceMessage -> "录音转文字后发送"
                    ConversationMode.VoiceCall -> "语音通话入口"
                    ConversationMode.Video -> "实时视频上下文入口"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = serverUrl,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = { },
                label = { Text(mode.name) },
                leadingIcon = {
                    Icon(modeIcon(mode), contentDescription = null)
                }
            )
            TextButton(onClick = onDisconnect) {
                Text("退出")
            }
        }
    }
}

@Composable
private fun Conversation(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, isThinking) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        if (isThinking) {
            item {
                MessageBubble(
                    ChatMessage(
                        speaker = Speaker.Assistant,
                        text = "Hermes 正在思考..."
                    )
                )
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.speaker == Speaker.User
    val isSystem = message.speaker == Speaker.System
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            isUser -> Arrangement.End
            isSystem -> Arrangement.Center
            else -> Arrangement.Start
        }
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = when {
                isUser -> MaterialTheme.colorScheme.primary
                isSystem -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            },
            tonalElevation = if (isUser) 0.dp else 1.dp,
            shadowElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth(if (isSystem) 0.72f else 0.84f)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                style = if (isSystem) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun Composer(
    mode: ConversationMode,
    input: String,
    selectedModel: String,
    models: List<String>,
    isListening: Boolean,
    voiceState: VoiceCallState,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onModelSelect: (String) -> Unit,
    onListen: () -> Unit,
    onStopListen: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(14.dp)
    ) {
        if (input.startsWith("/model")) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                models.forEach { model ->
                    AssistChip(
                        onClick = { onModelSelect(model) },
                        label = { Text(if (model == selectedModel) "$model 当前" else model) }
                    )
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        when (mode) {
                            ConversationMode.Text -> "和 Hermes 聊点什么，或输入 /model"
                            ConversationMode.VoiceMessage -> "语音消息会转成文字发送"
                            ConversationMode.VoiceCall -> "语音通话中也可以打字补充"
                            ConversationMode.Video -> "向 Hermes 描述你想让它看的内容"
                        }
                    )
                },
                minLines = 1,
                maxLines = 4
            )
            if (mode == ConversationMode.VoiceMessage || mode == ConversationMode.VoiceCall) {
                val micIcon = when {
                    isListening -> Icons.Default.Stop
                    voiceState == VoiceCallState.Speaking -> Icons.Default.GraphicEq
                    voiceState == VoiceCallState.Processing -> Icons.Default.GraphicEq
                    else -> Icons.Default.Mic
                }
                val micDescription = when (voiceState) {
                    VoiceCallState.Idle -> "开始说话"
                    VoiceCallState.Recording -> "停止录音"
                    VoiceCallState.Processing -> "等待回复..."
                    VoiceCallState.Speaking -> "播放中..."
                }
                val micColor = when (voiceState) {
                    VoiceCallState.Recording -> Color.Red
                    VoiceCallState.Processing -> MaterialTheme.colorScheme.tertiary
                    VoiceCallState.Speaking -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                IconButton(
                    onClick = if (isListening) onStopListen else onListen,
                    enabled = voiceState != VoiceCallState.Processing && voiceState != VoiceCallState.Speaking
                ) {
                    Icon(
                        imageVector = micIcon,
                        contentDescription = micDescription,
                        tint = micColor
                    )
                }
            }
            FilledIconButton(onClick = onSend) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "发送"
                )
            }
        }
    }
}

@Composable
private fun ApprovalCard(
    approval: ApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = approval.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = approval.description,
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onReject) {
                    Text("拒绝")
                }
                Spacer(Modifier.size(8.dp))
                Button(onClick = onApprove) {
                    Text("Approve")
                }
            }
        }
    }
}

@Composable
private fun VideoPanel(modifier: Modifier = Modifier) {
    var isCameraActive by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
        contentAlignment = Alignment.BottomCenter
    ) {
        VideoPreview(
            modifier = Modifier.fillMaxSize(),
            onCameraReady = { isCameraActive = true },
            onCameraError = { isCameraActive = false }
        )
        Text(
            text = if (isCameraActive) "实时画面传输中" else "相机未启动",
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun ErrorStrip(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ModeItem(
    mode: ConversationMode,
    selectedMode: ConversationMode,
    label: String,
    onClick: (ConversationMode) -> Unit
) {
    TextButton(onClick = { onClick(mode) }) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = modeIcon(mode),
                contentDescription = label,
                tint = if (selectedMode == mode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                text = label,
                color = if (selectedMode == mode) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun modeIcon(mode: ConversationMode) = when (mode) {
    ConversationMode.Text -> Icons.Default.Textsms
    ConversationMode.VoiceMessage -> Icons.Default.Mic
    ConversationMode.VoiceCall -> Icons.Default.GraphicEq
    ConversationMode.Video -> Icons.Default.Videocam
}
