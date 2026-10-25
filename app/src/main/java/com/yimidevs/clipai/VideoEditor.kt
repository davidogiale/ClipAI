package com.yimidevs.clipai

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** All video work happens on the phone with Media3 Transformer (hardware encoder, no FFmpeg). */
@OptIn(UnstableApi::class)
object VideoEditor {

    /** Fallback when the audio can't be copied directly: convert it to AAC first. */
    suspend fun exportAudioOnly(context: Context, uri: Uri, out: File, onProgress: (Int) -> Unit) {
        val item = EditedMediaItem.Builder(MediaItem.fromUri(uri)).setRemoveVideo(true).build()
        export(context, item, out, onProgress)
    }

    suspend fun renderClip(
        context: Context,
        video: VideoInfo,
        clip: ClipCandidate,
        words: List<Word>,
        options: ClipOptions,
        out: File,
        onProgress: (Int) -> Unit,
    ) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs((clip.start * 1000).toLong())
            .setEndPositionMs((clip.end * 1000).toLong())
            .build()
        val mediaItem = MediaItem.Builder().setUri(video.uri).setClippingConfiguration(clipping).build()

        val effects = mutableListOf<Effect>()
        val outWidth: Int
        val captionY: Float
        when (options.frame) {
            FrameMode.VERTICAL_CROP -> {
                effects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
                outWidth = 1080
                captionY = -0.45f
            }
            FrameMode.VERTICAL_FIT -> {
                effects.add(Presentation.createForWidthAndHeight(1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT))
                outWidth = 1080
                captionY = -0.45f
            }
            FrameMode.ORIGINAL -> {
                outWidth = if (video.width > 0) video.width else 1080
                captionY = -0.72f
            }
        }

        if (options.captions) {
            val clipWords = words.filter { it.end > clip.start && it.start < clip.end }
            if (clipWords.isNotEmpty()) {
                val size = (outWidth * 0.068f).toInt().coerceAtLeast(24)
                val overlay: TextureOverlay = CaptionOverlay(clipWords, clip.start, size, captionY)
                effects.add(OverlayEffect(ImmutableList.of(overlay)))
            }
        }

        val edited = EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(emptyList<AudioProcessor>(), effects))
            .build()
        export(context, edited, out, onProgress)
    }

    private suspend fun export(
        context: Context,
        item: EditedMediaItem,
        out: File,
        onProgress: (Int) -> Unit,
    ): Unit = withContext(Dispatchers.Main) {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        val handler = Handler(Looper.getMainLooper())

        suspendCancellableCoroutine<Unit> { cont ->
            lateinit var poll: Runnable
            val transformer = Transformer.Builder(context.applicationContext)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        handler.removeCallbacks(poll)
                        onProgress(100)
                        if (cont.isActive) cont.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        handler.removeCallbacks(poll)
                        out.delete()
                        if (cont.isActive) cont.resumeWithException(exportException)
                    }
                })
                .build()

            val holder = ProgressHolder()
            poll = Runnable {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    onProgress(holder.progress)
                }
                handler.postDelayed(poll, 400)
            }
            cont.invokeOnCancellation {
                handler.post {
                    handler.removeCallbacks(poll)
                    transformer.cancel()
                    out.delete()
                }
            }
            transformer.start(item, out.absolutePath)
            handler.postDelayed(poll, 400)
        }
    }
}
