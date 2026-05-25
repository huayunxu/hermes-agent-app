package com.hermes.agent.camera

import android.view.TextureView
import android.view.ViewGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun VideoPreview(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val previewView = remember {
        TextureView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}
