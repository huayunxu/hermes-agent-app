package com.hermes.agent

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.hermes.agent.agent.LocalHermesAgentService
import com.hermes.agent.auth.HermesSessionStore
import com.hermes.agent.ui.HermesApp
import com.hermes.agent.ui.HermesTheme
import com.hermes.agent.viewmodel.HermesViewModel
import com.hermes.agent.voice.AndroidVoiceController

class MainActivity : ComponentActivity() {
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val cameraGranted = permissions[Manifest.permission.CAMERA] ?: false
        // 后续可以根据权限状态更新 UI
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 权限请求放在 setContent 之前，避免延迟或丢失
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            )
        )

        setContent {
            val voiceController = remember {
                AndroidVoiceController(applicationContext)
            }
            val sessionStore = remember {
                HermesSessionStore(applicationContext)
            }
            DisposableEffect(voiceController) {
                onDispose { voiceController.shutdown() }
            }
            val viewModel = remember {
                HermesViewModel(
                    agentService = LocalHermesAgentService(),
                    voiceController = voiceController,
                    sessionStore = sessionStore,
                    initialSession = sessionStore.load()
                )
            }

            HermesTheme {
                HermesApp(viewModel = viewModel)
            }
        }
    }
}
