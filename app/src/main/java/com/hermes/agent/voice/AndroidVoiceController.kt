package com.hermes.agent.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Android voice controller with dual-mode speech recognition:
 * 1. Primary: Android SpeechRecognizer (built-in STT)
 * 2. Fallback: Raw PCM AudioRecord (records and returns audio bytes for server-side transcription)
 *
 * TTS uses Android TextToSpeech for reply playback.
 */
class AndroidVoiceController(
    private val context: Context
) : VoiceController {

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsInitialized = false
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var currentScope: CoroutineScope? = null

    private var pendingOnResult: ((String) -> Unit)? = null
    private var pendingOnError: ((String) -> Unit)? = null
    private var pendingRawResult: ((ByteArray) -> Unit)? = null

    private val tag = "AndroidVoiceController"

    init {
        // Initialize TTS eagerly
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsInitialized = true
                val result = tts?.setLanguage(Locale.forLanguageTag("zh-CN"))
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(tag, "TTS Chinese not supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                Log.d(tag, "TTS initialized successfully")
            } else {
                Log.e(tag, "TTS initialization failed: $status")
            }
        }
    }

    override fun isSpeechRecognizerAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        // Store callbacks for use in raw recording fallback
        pendingOnResult = onResult
        pendingOnError = onError

        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w(tag, "SpeechRecognizer not available, switching to raw audio recording")
            startRawRecording(onResult, onError)
            return
        }

        stopCurrentRecognition()
        stopRawRecording()

        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d(tag, "Ready for speech")
                }
                override fun onBeginningOfSpeech() {
                    Log.d(tag, "Beginning of speech")
                }
                override fun onRmsChanged(rmsdB: Float) {
                    // Could be used for visual feedback
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    Log.d(tag, "End of speech")
                }
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别超时，请检查网络"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入，请重试"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制失败"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少音频权限"
                        else -> "语音识别失败，错误码: $error"
                    }
                    Log.e(tag, "SpeechRecognizer error: $error - $errorMsg")
                    // For network-related errors, fall back to raw recording
                    if (error in listOf(
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                            SpeechRecognizer.ERROR_SERVER,
                            SpeechRecognizer.ERROR_CLIENT
                        )
                    ) {
                        Log.w(tag, "Network error, falling back to raw audio recording")
                        startRawRecording(pendingOnResult!!, pendingOnError!!)
                    } else {
                        pendingOnError?.invoke(errorMsg)
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    val text = matches.firstOrNull().orEmpty()
                    if (text.isBlank()) {
                        pendingOnError?.invoke("没有识别到有效语音，请重试")
                    } else {
                        Log.d(tag, "Speech recognition result: $text")
                        pendingOnResult?.invoke(text)
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                // Use zh-CN for Chinese, fall back to system default
                val locale = Locale.getDefault()
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            sr.startListening(intent)
            Log.d(tag, "Started SpeechRecognizer")
        }
    }

    /**
     * Fallback: record raw PCM audio using AudioRecord.
     * Returns a WAV header-prefixed PCM byte array via the onResult callback.
     * The caller should upload this to the gateway for server-side transcription.
     */
    private fun startRawRecording(onResult: (String) -> Unit, onError: (String) -> Unit) {
        val scope = CoroutineScope(Dispatchers.IO)
        currentScope = scope

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT
        )
        if (bufferSize <= 0) {
            onError("设备不支持音频录制")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                onError("音频录制初始化失败")
                return
            }

            audioRecord?.startRecording()
            Log.d(tag, "AudioRecord started")

            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(bufferSize)
            val maxDurationMs = 30_000 // Max 30 seconds
            val startTime = System.currentTimeMillis()

            recordingJob = scope.launch {
                try {
                    while (isActive && (System.currentTimeMillis() - startTime) < maxDurationMs) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            outputStream.write(buffer, 0, read)
                        }
                        // Small delay to avoid tight loop
                        delay(10)
                    }

                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null

                    val pcmBytes = outputStream.toByteArray()
                    val wavBytes = addWavHeader(pcmBytes, SAMPLE_RATE, 1, 16)

                    Log.d(tag, "Raw recording complete: ${wavBytes.size} bytes")
                    // Return the audio data - caller should upload to gateway
                    // For now, provide a placeholder that indicates audio was captured
                    pendingRawResult?.invoke(wavBytes)
                    onResult("[AUDIO:${wavBytes.size}]") // Placeholder - gateway should transcribe
                } catch (e: Exception) {
                    Log.e(tag, "Recording error: ${e.message}")
                    audioRecord?.stop()
                    audioRecord?.release()
                    audioRecord = null
                    onError("录音失败: ${e.message}")
                }
            }
        } catch (e: SecurityException) {
            onError("缺少麦克风权限，请在设置中授权")
        } catch (e: Exception) {
            onError("音频录制失败: ${e.message}")
        }
    }

    private fun stopRawRecording() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        currentScope = null
    }

    private fun stopCurrentRecognition() {
        recognizer?.stopListening()
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
    }

    override fun stopListening() {
        stopCurrentRecognition()
        stopRawRecording()
    }

    override fun speak(text: String) {
        if (!ttsInitialized) {
            Log.w(tag, "TTS not initialized, skipping speak")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-agent")
    }

    override fun stopSpeaking() {
        tts?.stop()
    }

    override fun shutdown() {
        stopListening()
        stopSpeaking()
        tts?.shutdown()
        tts = null
        ttsInitialized = false
    }

    /**
     * Add a minimal WAV header to PCM data.
     */
    private fun addWavHeader(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val fileSize = 36 + dataSize

        val header = ByteArray(44)
        // RIFF header
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        // File size - 8
        header[4] = (fileSize and 0xff).toByte()
        header[5] = ((fileSize shr 8) and 0xff).toByte()
        header[6] = ((fileSize shr 16) and 0xff).toByte()
        header[7] = ((fileSize shr 24) and 0xff).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt chunk
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        // Subchunk1Size (16 for PCM)
        header[16] = 16
        header[17] = 0
        header[18] = 0
        header[19] = 0
        // AudioFormat (1 = PCM)
        header[20] = 1
        header[21] = 0
        // NumChannels
        header[22] = channels.toByte()
        header[23] = 0
        // SampleRate
        header[24] = (sampleRate and 0xff).toByte()
        header[25] = ((sampleRate shr 8) and 0xff).toByte()
        header[26] = ((sampleRate shr 16) and 0xff).toByte()
        header[27] = ((sampleRate shr 24) and 0xff).toByte()
        // ByteRate
        header[28] = (byteRate and 0xff).toByte()
        header[29] = ((byteRate shr 8) and 0xff).toByte()
        header[30] = ((byteRate shr 16) and 0xff).toByte()
        header[31] = ((byteRate shr 24) and 0xff).toByte()
        // BlockAlign
        header[32] = blockAlign.toByte()
        header[33] = 0
        // BitsPerSample
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        // data chunk
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        // Data size
        header[40] = (dataSize and 0xff).toByte()
        header[41] = ((dataSize shr 8) and 0xff).toByte()
        header[42] = ((dataSize shr 16) and 0xff).toByte()
        header[43] = ((dataSize shr 24) and 0xff).toByte()

        return header + pcmData
    }

    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
}