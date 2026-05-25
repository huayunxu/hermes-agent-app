package com.hermes.agent.voice

interface VoiceController {
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)
    fun stopListening()
    fun speak(text: String)
    fun shutdown()
}

