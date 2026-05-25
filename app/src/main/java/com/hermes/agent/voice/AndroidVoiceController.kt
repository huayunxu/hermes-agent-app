package com.hermes.agent.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale

class AndroidVoiceController(
    private val context: Context
) : VoiceController {
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var logger: (String) -> Unit = { /* TODO: wire to Logcat */ }

    override fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("当前设备不支持系统语音识别。")
            return
        }

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { speechRecognizer ->
            speechRecognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别超时，请检查网络"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未检测到语音输入"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到有效语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "语音识别服务忙碌"
                        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_AUDIO -> "音频录制失败"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "缺少音频权限"
                        else -> "语音识别失败，错误码: $error"
                    }
                    logger("SpeechRecognizer error: $error - $errorMsg")
                    onError(errorMsg)
                }

                override fun onResults(results: Bundle?) {
                    val matches = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        .orEmpty()
                    val text = matches.firstOrNull().orEmpty()
                    if (text.isBlank()) {
                        onError("没有识别到有效语音。")
                    } else {
                        onResult(text)
                    }
                }
            })

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }
            speechRecognizer.startListening(intent)
        }
    }

    override fun stopListening() {
        recognizer?.stopListening()
    }

    override fun speak(text: String) {
        val existingTts = tts
        if (existingTts != null) {
            existingTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-agent")
            return
        }

        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val lang = Locale.SIMPLIFIED_CHINESE
                val result = tts?.setLanguage(lang)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    logger("TTS 中文不支持，使用系统默认语言")
                    tts?.language = Locale.getDefault()
                }
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-agent")
            } else {
                logger("TTS 初始化失败: $status")
            }
        }
    }

    override fun shutdown() {
        recognizer?.destroy()
        recognizer = null
        tts?.shutdown()
        tts = null
    }
}

