package com.hermes.agent.agent

import com.hermes.agent.data.AgentReply
import com.hermes.agent.data.ChatMessage

interface HermesAgentService {
    suspend fun sendText(history: List<ChatMessage>, text: String, model: String): AgentReply
    suspend fun sendApproval(approvalId: String, approved: Boolean): AgentReply
    suspend fun setModel(model: String)
    suspend fun startVoiceSession()
    suspend fun startVideoSession()
}
