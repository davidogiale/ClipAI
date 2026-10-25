package com.yimidevs.clipai

import android.app.Application
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ClipUi(
    val clip: ClipCandidate,
    val selected: Boolean = true,
    val renderProgress: Int? = null,
    val output: File? = null,
    val saved: Boolean = false,
    val error: String? = null,
)

data class UiState(
    val video: VideoInfo? = null,
    val options: ClipOptions = ClipOptions(),
    val working: Boolean = false,
    val rendering: Boolean = false,
    val status: String = "",
    val progress: Float? = null,
    val log: List<String> = emptyList(),
    val error: String? = null,
    val transcript: Transcript? = null,
    val clips: List<ClipUi> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val settingsStore = SettingsStore(app)
    private val ai = AiClient()
    private val finder = HighlightFinder(ai)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    private val ctx: Context get() = getApplication()
    private val clipsDir: File get() = File(ctx.filesDir, "clips")

    // ---------- video ----------

    fun setVideo(uri: Uri) {
        val s = _state.value
        if (s.working || s.rendering) return
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    clipsDir.deleteRecursively()
                    readVideoInfo(ctx, uri)
                }
                _state.update { UiState(video = info, options = it.options) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't open that video: ${e.message}") }
            }
        }
    }

    fun setOptions(o: ClipOptions) {
        val min = o.minSec.coerceIn(5, 170)
        val max = maxOf(o.maxSec, min + 5)
        _state.update { it.copy(options = o.copy(minSec = min, maxSec = max)) }
    }

    // ---------- find clips ----------

    fun findClips() {
        val s0 = _state.value
        val video = s0.video ?: return
        if (s0.working || s0.rendering) return
        val settings = settingsStore.load()
        if (!settings.stt.isReady) {
            _state.update { it.copy(error = "Add your free Groq API key in Settings first (it's used to turn speech into text).") }
            return
        }
        if (!settings.llm.isReady) {
            _state.update { it.copy(error = "Add an API key and model for the clip-finding AI in Settings.") }
            return
        }
        job = viewModelScope.launch {
            _state.update { it.copy(working = true, error = null, clips = emptyList(), log = emptyList(), progress = null) }
            try {
                // The transcript is kept, so trying different clip lengths doesn't re-upload the audio.
                val transcript = _state.value.transcript ?: transcribe(video, settings).also { tr ->
                    _state.update { it.copy(transcript = tr) }
                }
                if (transcript.segments.isEmpty()) {
                    throw IllegalStateException("No speech was found in this video. AI clipping needs a video with talking.")
                }
                status("AI is picking the best moments…", null)
                val clips = finder.find(
                    settings.llm, transcript, video.durationSec, _state.value.options, settings.chunkChars,
                ) { msg -> status(msg, null) }
                if (clips.isEmpty()) {
                    throw IllegalStateException(
                        "The AI didn't return usable clips. Try again, change the clip length, or pick another model in Settings.",
                    )
                }
                _state.update { it.copy(clips = clips.map { c -> ClipUi(c) }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(working = false, status = "", progress = null) }
            }
        }
    }

    private suspend fun transcribe(video: VideoInfo, settings: AppSettings): Transcript {
        status("Extracting audio…", null)
        val dir = File(ctx.cacheDir, "audio")
        withContext(Dispatchers.IO) { dir.deleteRecursively(); dir.mkdirs() }

        val chunks = try {
            withContext(Dispatchers.IO) { AudioChunker.split(ctx, video.uri, dir) }
        } catch (e: NoAudioException) {
            throw e
        } catch (e: Exception) {
            log("Couldn't copy the audio directly (${e.message}). Converting it instead…")
            val full = File(dir, "full_audio.m4a")
            VideoEditor.exportAudioOnly(ctx, video.uri, full) { p -> status("Converting audio… $p%", p / 100f) }
            withContext(Dispatchers.IO) { AudioChunker.split(ctx, Uri.fromFile(full), File(dir, "parts")) }
        }

        val segments = mutableListOf<Segment>()
        val words = mutableListOf<Word>()
        chunks.forEachIndexed { i, c ->
            status("Transcribing speech (part ${i + 1} of ${chunks.size})…", i.toFloat() / chunks.size)
            val t = ai.transcribe(settings.stt, c.file, c.startSec) { msg -> log(msg) }
            segments.addAll(t.segments)
            words.addAll(t.words)
        }
        log("Transcribed ${segments.size} sentences.")
        return Transcript(segments, words)
    }

    // ---------- clip list ----------

    fun toggle(id: Int) = updateClip(id) { it.copy(selected = !it.selected) }

    /** Move a clip's start/end by a second, e.g. to keep a word that got cut off. */
    fun nudge(id: Int, startDelta: Double, endDelta: Double) {
        val dur = _state.value.video?.durationSec ?: return
        updateClip(id) { ui ->
            val c = ui.clip
            val start = (c.start + startDelta).coerceIn(0.0, c.end - 3.0)
            val end = (c.end + endDelta).coerceIn(start + 3.0, dur)
            ui.output?.delete()
            ui.copy(clip = c.copy(start = start, end = end), output = null, saved = false, error = null)
        }
    }

    fun renderSelected() {
        val s = _state.value
        val video = s.video ?: return
        if (s.rendering || s.working) return
        val targets = s.clips.filter { it.selected && it.output == null }.map { it.clip.id }
        if (targets.isEmpty()) return

        job = viewModelScope.launch {
            _state.update { it.copy(rendering = true, error = null) }
            try {
                for (id in targets) {
                    val ui = _state.value.clips.firstOrNull { it.clip.id == id } ?: continue
                    updateClip(id) { it.copy(renderProgress = 0, error = null) }
                    try {
                        val out = File(clipsDir, "clip_${id}_${System.currentTimeMillis()}.mp4")
                        VideoEditor.renderClip(
                            ctx, video, ui.clip,
                            _state.value.transcript?.words.orEmpty(),
                            _state.value.options, out,
                        ) { p -> updateClip(id) { it.copy(renderProgress = p) } }
                        updateClip(id) { it.copy(renderProgress = null, output = out, saved = false) }
                    } catch (e: CancellationException) {
                        updateClip(id) { it.copy(renderProgress = null) }
                        throw e
                    } catch (e: Exception) {
                        updateClip(id) { it.copy(renderProgress = null, error = "Render failed: ${e.message}") }
                    }
                }
            } finally {
                _state.update { it.copy(rendering = false) }
            }
        }
    }

    fun saveToGallery(id: Int) {
        val ui = _state.value.clips.firstOrNull { it.clip.id == id } ?: return
        val file = ui.output ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { saveVideoToGallery(ctx, file, ui.clip.title) }
                updateClip(id) { it.copy(saved = true) }
                Toast.makeText(ctx, "Saved to Movies/ClipAI", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(ctx, "Couldn't save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    // ---------- settings helpers ----------

    suspend fun loadModels(s: AppSettings): List<String> {
        val all = ai.listModels(s.llm).map { it.removePrefix("models/") }
        val nonChat = listOf("whisper", "tts", "embed", "guard", "orpheus", "imagen", "image", "veo", "native-audio", "aqa")
        return all.filter { id ->
            val l = id.lowercase()
            if ("openrouter" in s.llmBaseUrl) id.endsWith(":free") || id == "openrouter/free"
            else nonChat.none { it in l }
        }.distinct().sorted()
    }

    suspend fun testConnections(s: AppSettings): String {
        val stt = try {
            if (!s.stt.isReady) "Speech-to-text: add a key" else {
                ai.listModels(s.stt)
                "Speech-to-text: ✓ key works"
            }
        } catch (e: Exception) {
            "Speech-to-text: ✗ ${e.message}"
        }
        val llm = try {
            if (!s.llm.isReady) "Clip AI: add a key and model" else {
                ai.chat(s.llm, "You reply in JSON.", "Reply with {\"ok\":true} as JSON.")
                "Clip AI: ✓ ${s.llmModel} replied"
            }
        } catch (e: Exception) {
            "Clip AI: ✗ ${e.message}"
        }
        return "$stt\n$llm"
    }

    // ---------- internals ----------

    private fun status(msg: String, progress: Float?) = _state.update { it.copy(status = msg, progress = progress) }

    private fun log(msg: String) = _state.update { it.copy(log = (it.log + msg).takeLast(6)) }

    private fun updateClip(id: Int, f: (ClipUi) -> ClipUi) = _state.update { s ->
        s.copy(clips = s.clips.map { if (it.clip.id == id) f(it) else it })
    }

    private fun readVideoInfo(context: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            if (durMs <= 0) throw IllegalStateException("couldn't read the video length")
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Video"
            return VideoInfo(uri, name, durMs / 1000.0, w, h)
        } finally {
            r.release()
        }
    }
}
