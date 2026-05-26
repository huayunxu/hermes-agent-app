package com.hermes.agent.voice

interface VoiceController {
    /**
     * Check if Android SpeechRecognizer is available on this device.
     */
    fun isSpeechRecognizerAvailable(): Boolean

    /**
     * Start listening for speech using Android SpeechRecognizer.
     * Falls back to AudioRecord if SpeechRecognizer is not available.
     */
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit)

    /**
     * Stop active speech recognition.
     */
    fun stopListening()

    /**
     * Play text as speech via TTS.
     */
    fun speak(text: String)

    /**
     * Stop TTS playback.
     */
    fun stopSpeaking()

    /**
     * Release all resources.
     */
    fun shutdown()
}