package com.hermes.agent.camera

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executor

private const val TAG = "VideoPreview"

/**
 * CameraX-based video preview that displays the device camera feed.
 * Falls back gracefully if camera is not available.
 */
@Composable
fun VideoPreview(
    modifier: Modifier = Modifier,
    onCameraReady: (() -> Unit)? = null,
    onCameraError: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var cameraState by remember { mutableStateOf<CameraState>(CameraState.Loading) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    LaunchedEffect(Unit) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()
                    bindCameraPreview(
                        cameraProvider = cameraProvider,
                        lifecycleOwner = lifecycleOwner,
                        previewView = previewView,
                        context = context,
                        onCameraReady = {
                            cameraState = CameraState.Active
                            onCameraReady?.invoke()
                        },
                        onCameraError = { error ->
                            cameraState = CameraState.Error(error)
                            onCameraError?.invoke(error)
                        }
                    )
                } catch (e: Exception) {
                    val error = "无法初始化相机: ${e.message}"
                    Log.e(TAG, error, e)
                    cameraState = CameraState.Error(error)
                    onCameraError?.invoke(error)
                }
            }, ContextCompat.getMainExecutor(context))
        } catch (e: Exception) {
            val error = "相机服务不可用: ${e.message}"
            Log.e(TAG, error, e)
            cameraState = CameraState.Error(error)
            onCameraError?.invoke(error)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        cameraProvider.unbindAll()
                    } catch (_: Exception) {}
                }, ContextCompat.getMainExecutor(context))
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        when (cameraState) {
            is CameraState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "正在启动相机...",
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 60.dp)
                    )
                }
            }
            is CameraState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = Color.White.copy(alpha = 0.3f)
                    )
                    Text(
                        text = (cameraState as CameraState.Error).message,
                        color = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 60.dp)
                    )
                }
            }
            CameraState.Active -> {
                // Camera is showing, no overlay needed
            }
        }
    }
}

private fun bindCameraPreview(
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    context: Context,
    onCameraReady: () -> Unit,
    onCameraError: (String) -> Unit
) {
    val preview = Preview.Builder()
        .build()

    val cameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .build()

    try {
        cameraProvider.unbindAll()
        val camera = cameraProvider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            preview
        )
        preview.setSurfaceProvider(previewView.surfaceProvider)
        onCameraReady()
    } catch (e: Exception) {
        onCameraError("相机启动失败: ${e.message}")
    }
}

private sealed class CameraState {
    data object Loading : CameraState()
    data object Active : CameraState()
    data class Error(val message: String) : CameraState()
}