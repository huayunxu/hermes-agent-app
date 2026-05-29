package com.hermes.agent.voice

interface VoiceController {
    /** Android SpeechRecognizer (on-device STT). */
    fun isSpeechRecognizerAvailable(): Boolean

    /** SpeechRecognizer or raw microphone recording for server-side transcription. */
    fun isVoiceInputAvailable(): Boolean

    /**
     * Start listening. [onResult] receives transcribed text from device STT.
     * [onRawAudio] receives WAV bytes when falling back to AudioRecord (upload via Gateway).
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        onRawAudio: ((ByteArray) -> Unit)? = null
    )

    fun stopListening()

    fun speak(text: String, onDone: (() -> Unit)? = null)

    fun stopSpeaking()

    fun shutdown()
}
