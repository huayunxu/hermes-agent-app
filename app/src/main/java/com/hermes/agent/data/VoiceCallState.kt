package com.hermes.agent.data

/**
 * Voice call state for UI feedback.
 */
enum class VoiceCallState {
    /** Waiting for user to press mic */
    Idle,
    /** User is speaking / recording */
    Recording,
    /** Waiting for Hermes response */
    Processing,
    /** Hermes is speaking (TTS playback) */
    Speaking
}