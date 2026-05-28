package com.hermes.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Raw PCM audio recorder using Android AudioRecord API.
 * Falls back when device has no SpeechRecognizer.
 *
 * Captures 16-bit mono PCM at 16kHz.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        val BUFFER_SIZE: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null

    private val _isRecording = MutableStateFlow(false)
    val isRecordingFlow: StateFlow<Boolean> = _isRecording

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun startRecording(): Result<Unit> = withContext(Dispatchers.IO) {
        if (isRecording) return@withContext Result.success(Unit)
        if (!hasPermission()) {
            return@withContext Result.failure(SecurityException("RECORD_AUDIO permission not granted"))
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return@withContext Result.failure(IllegalStateException("AudioRecord failed to initialize"))
            }

            isRecording = true
            _isRecording.value = true
            audioRecord?.startRecording()
            Result.success(Unit)
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            Result.failure(e)
        }
    }

    suspend fun stopRecording(): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (!isRecording) {
            return@withContext Result.success(ByteArray(0))
        }

        isRecording = false
        _isRecording.value = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            // Note: Actual PCM bytes are collected during recording via the buffer
            // For this implementation, we return an empty byte array as placeholder
            // The actual recording flow sends chunks to the gateway in real-time
            Result.success(ByteArray(0))
        } catch (e: Exception) {
            audioRecord?.release()
            audioRecord = null
            Result.failure(e)
        }
    }

    suspend fun captureChunk(): ByteArray = withContext(Dispatchers.IO) {
        val buffer = ByteArray(BUFFER_SIZE)
        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
        if (read > 0) {
            buffer.copyOf(read)
        } else {
            ByteArray(0)
        }
    }

    fun shutdown() {
        isRecording = false
        _isRecording.value = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}