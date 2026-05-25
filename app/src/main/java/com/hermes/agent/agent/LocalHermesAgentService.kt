package com.hermes.agent.agent

import com.hermes.agent.data.AgentReply
import com.hermes.agent.data.ChatMessage
import kotlinx.coroutines.delay

class LocalHermesAgentService : HermesAgentService {
    private var activeModel: String = "default"

    override suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply {
        delay(500)
        val reply = when {
            text.contains("approve", ignoreCase = true) || text.contains("审批") ->
                return AgentReply(
                    text = "这个操作需要你确认后才能继续。",
                    approvalRequest = com.hermes.agent.data.ApprovalRequest(
                        id = "local-approval",
                        title = "确认执行 Hermes 操作",
                        description = "原型环境中的审批卡片。真实后端可在这里请求运行工具、写文件或调用外部服务。"
                    )
                )
            text.contains("视频") -> "我已经切到视频能力的原型入口。接入后端视觉模型后，可以分析镜头内容、共享屏幕上下文，并进行实时对话。"
            text.contains("语音") -> "语音模式已经准备好。现在会使用系统语音识别转文字，并用文字转语音读出回复。"
            text.contains("安卓") || text.contains("Android", ignoreCase = true) ->
                "安卓端建议先把文字、语音和视频做成统一会话流，后端负责记忆、工具调用和多模态理解。"
            else -> "收到：$text\n\n当前模型：$activeModel。接入真实 Agent 服务后，这里会返回模型生成的答案。"
        }
        return AgentReply(text = reply)
    }

    override suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply {
        delay(200)
        return AgentReply(
            text = if (approved) "已批准操作，Hermes 会继续执行。" else "已拒绝操作，Hermes 不会继续执行。"
        )
    }

    override suspend fun setModel(model: String) {
        activeModel = model
    }

    override suspend fun startVoiceSession() {
        delay(100)
    }

    override suspend fun startVideoSession() {
        delay(100)
    }
}
