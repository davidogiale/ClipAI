package com.yimidevs.clipai

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleShare(intent)
        setContent {
            ClipAITheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** A video shared into the app from Gallery/Files/YouTube-downloader etc. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        if (intent.type?.startsWith("video/") != true) return
        streamUri(intent)?.let { vm.setVideo(it) }
    }

    @Suppress("DEPRECATION")
    private fun streamUri(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
}

val BrandGreen = Color(0xFF22C55E)

@Composable
fun ClipAITheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BrandGreen,
            onPrimary = Color(0xFF052E16),
            primaryContainer = Color(0xFF14532D),
            onPrimaryContainer = Color(0xFFDCFCE7),
            secondary = Color(0xFF86EFAC),
            background = Color(0xFF0A0F0B),
            surface = Color(0xFF0A0F0B),
            surfaceVariant = Color(0xFF151F18),
            onSurfaceVariant = Color(0xFFB7C4BA),
        ),
        content = content,
    )
}
