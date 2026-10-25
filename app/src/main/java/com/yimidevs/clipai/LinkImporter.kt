package com.yimidevs.clipai

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.MediaFormat as NpFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/** Downloads a video from a YouTube, Google Drive or direct video link into the app's storage. */
class LinkImporter {

    data class Result(val file: File, val title: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val newPipeReady by lazy {
        NewPipe.init(NewPipeDownloader(client))
        true
    }

    suspend fun import(
        text: String,
        dir: File,
        maxHeight: Int,
        onStatus: (String, Float?) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val url = extractUrl(text) ?: throw IllegalArgumentException("That doesn't look like a link. Paste the full https:// link.")
        dir.mkdirs()
        val driveId = driveId(url)
        when {
            isYouTube(url) -> youtube(url, dir, maxHeight, onStatus)
            driveId != null -> drive(driveId, dir, onStatus)
            else -> direct(url, dir, onStatus)
        }
    }

    // ---------------- YouTube ----------------

    private suspend fun youtube(url: String, dir: File, maxHeight: Int, onStatus: (String, Float?) -> Unit): Result {
        check(newPipeReady)
        onStatus("Reading the YouTube link…", null)
        val info = try {
            StreamInfo.getInfo(url)
        } catch (e: Exception) {
            throw IOException(youtubeError(e), e)
        }

        // YouTube serves HD as separate video and audio files; download both and join them.
        val video = info.videoOnlyStreams
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.format == NpFormat.MPEG_4 &&
                    (it.codec ?: "").startsWith("avc1") && // H.264 plays and edits on every phone
                    it.height in 1..maxHeight
            }
            .maxByOrNull { it.height * 1000 + it.fps }
        val audio = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == NpFormat.M4A }
            .sortedWith(
                compareByDescending<org.schabi.newpipe.extractor.stream.AudioStream> {
                    it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL
                }.thenByDescending { it.averageBitrate },
            )
            .firstOrNull()

        val out = File(dir, "youtube.mp4")
        if (video != null && audio != null) {
            val vf = File(dir, "yt_video.mp4")
            val af = File(dir, "yt_audio.m4a")
            download(audio.content, af, rejectHtml = false) { done, total ->
                onStatus("Downloading audio… ${progressText(done, total)}", fraction(done, total))
            }
            download(video.content, vf, rejectHtml = false) { done, total ->
                onStatus("Downloading ${video.height}p video… ${progressText(done, total)}", fraction(done, total))
            }
            onStatus("Joining audio and video…", null)
            mergeAudioVideo(vf, af, out)
            vf.delete()
            af.delete()
        } else {
            // Fallback: a single file that already has sound (usually 360p).
            val muxed = info.videoStreams
                .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.format == NpFormat.MPEG_4 }
                .maxByOrNull { it.height }
                ?: throw IOException("YouTube didn't offer a downloadable MP4 for this video.")
            download(muxed.content, out, rejectHtml = false) { done, total ->
                onStatus("Downloading video… ${progressText(done, total)}", fraction(done, total))
            }
        }
        return Result(out, info.name.ifBlank { "YouTube video" })
    }

    private fun youtubeError(e: Exception): String = when (e) {
        is SignInConfirmNotBotException, is ReCaptchaException ->
            "YouTube is blocking downloads from this network right now (it asked to confirm you're not a bot). " +
                "Try again later or on another network (e.g. switch between Wi-Fi and mobile data)."
        is AgeRestrictedContentException -> "This video is age-restricted, so it can't be downloaded without signing in."
        is PrivateContentException -> "This video is private."
        is PaidContentException -> "This is paid/members-only content."
        is GeographicRestrictionException -> "This video isn't available in your country."
        is ContentNotAvailableException -> "This video isn't available: ${e.message}"
        else -> "Couldn't read this YouTube video (${e.message}). YouTube changes often; updating the app's " +
            "NewPipeExtractor version usually fixes this."
    }

    // ---------------- Google Drive ----------------

    private suspend fun drive(id: String, dir: File, onStatus: (String, Float?) -> Unit): Result {
        val out = File(dir, "drive_video.mp4")
        val url = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
        val meta = try {
            download(url, out, rejectHtml = true) { done, total ->
                onStatus("Downloading from Google Drive… ${progressText(done, total)}", fraction(done, total))
            }
        } catch (e: HtmlInsteadOfVideoException) {
            throw IOException(
                "Google Drive didn't send the video. Open the file in Drive → Share → General access → " +
                    "\"Anyone with the link\", then paste the link again.",
            )
        }
        return Result(out, meta.fileName ?: "Drive video")
    }

    // ---------------- Direct link (…/video.mp4) ----------------

    private suspend fun direct(url: String, dir: File, onStatus: (String, Float?) -> Unit): Result {
        val out = File(dir, "linked_video.mp4")
        val meta = try {
            download(url, out, rejectHtml = true) { done, total ->
                onStatus("Downloading video… ${progressText(done, total)}", fraction(done, total))
            }
        } catch (e: HtmlInsteadOfVideoException) {
            throw IOException("That link opens a web page, not a video file. Supported: YouTube, Google Drive, or a direct .mp4 link.")
        }
        val name = meta.fileName ?: Uri.parse(url).lastPathSegment ?: "Linked video"
        return Result(out, name)
    }

    // ---------------- downloading ----------------

    private class HtmlInsteadOfVideoException : IOException("Got a web page instead of a video")
    private data class DownloadMeta(val contentType: String?, val fileName: String?)

    /**
     * Downloads in 8 MB ranges (YouTube throttles single long downloads), falling back to a
     * normal download when the server ignores ranges.
     */
    private suspend fun download(
        url: String,
        out: File,
        rejectHtml: Boolean,
        onProgress: (Long, Long) -> Unit,
    ): DownloadMeta {
        val chunk = 8L * 1024 * 1024
        var pos = 0L
        var total = -1L
        var finished = false
        var meta: DownloadMeta? = null
        val buffer = ByteArray(256 * 1024)

        out.outputStream().buffered().use { os ->
            while (!finished) {
                currentCoroutineContext().ensureActive()
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$pos-${pos + chunk - 1}")
                    .build()
                client.newCall(request).execute().use { r ->
                    if (r.code == 416 && pos > 0) {
                        finished = true
                    } else {
                        if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code}).")
                        val body = r.body ?: throw IOException("The server sent nothing.")
                        if (meta == null) {
                            val type = r.header("Content-Type")
                            if (rejectHtml && type?.startsWith("text/html") == true) throw HtmlInsteadOfVideoException()
                            meta = DownloadMeta(type, fileNameFrom(r.header("Content-Disposition")))
                        }
                        val ranged = r.code == 206
                        if (ranged) {
                            total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: total
                        } else {
                            total = body.contentLength()
                        }
                        var readThisRequest = 0L
                        val input = body.byteStream()
                        var n = input.read(buffer)
                        while (n >= 0) {
                            os.write(buffer, 0, n)
                            pos += n
                            readThisRequest += n
                            onProgress(pos, total)
                            currentCoroutineContext().ensureActive()
                            n = input.read(buffer)
                        }
                        input.close()
                        finished = !ranged || readThisRequest == 0L ||
                            (total > 0 && pos >= total) || (total <= 0 && readThisRequest < chunk)
                    }
                }
            }
        }
        if (pos == 0L) throw IOException("The download was empty.")
        return meta ?: DownloadMeta(null, null)
    }

    private fun fileNameFrom(disposition: String?): String? {
        if (disposition == null) return null
        Regex("filename\\*=UTF-8''([^;]+)").find(disposition)?.let { return Uri.decode(it.groupValues[1]) }
        return Regex("filename=\"?([^\";]+)\"?").find(disposition)?.groupValues?.get(1)
    }

    private fun fraction(done: Long, total: Long): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

    private fun progressText(done: Long, total: Long): String {
        val mb = done / 1_048_576.0
        return if (total > 0) "%.0f / %.0f MB".format(mb, total / 1_048_576.0) else "%.0f MB".format(mb)
    }

    // ---------------- joining YouTube's separate audio + video ----------------

    private fun mergeAudioVideo(videoFile: File, audioFile: File, out: File) {
        if (out.exists()) out.delete()
        val ve = MediaExtractor()
        val ae = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            ve.setDataSource(videoFile.absolutePath)
            ae.setDataSource(audioFile.absolutePath)
            val vt = firstTrack(ve, "video/")
            val at = firstTrack(ae, "audio/")
            ve.selectTrack(vt)
            ae.selectTrack(at)
            val vFormat = ve.getTrackFormat(vt)
            val aFormat = ae.getTrackFormat(at)
            val m = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = m
            val outV = m.addTrack(vFormat)
            val outA = m.addTrack(aFormat)
            m.start()

            val maxIn = listOf(vFormat, aFormat).maxOf {
                if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            }
            val buf = ByteBuffer.allocateDirect(maxOf(maxIn, 4 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            var vDone = false
            var aDone = false
            while (!vDone || !aDone) {
                val useVideo = !vDone && (aDone || ve.sampleTime <= ae.sampleTime)
                val ex = if (useVideo) ve else ae
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) {
                    if (useVideo) vDone = true else aDone = true
                    continue
                }
                val flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                m.writeSampleData(if (useVideo) outV else outA, buf, info)
                ex.advance()
            }
            m.stop()
        } finally {
            runCatching { muxer?.release() }
            ve.release()
            ae.release()
        }
    }

    private fun firstTrack(ex: MediaExtractor, prefix: String): Int =
        (0 until ex.trackCount).firstOrNull {
            ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true
        } ?: throw IOException("The downloaded file has no ${prefix.trimEnd('/')} track.")

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        fun extractUrl(text: String): String? =
            Regex("https?://[^\\s<>\"]+").find(text)?.value?.trimEnd(')', '.', ',', ';')

        fun isYouTube(url: String): Boolean {
            val host = Uri.parse(url).host?.lowercase() ?: return false
            return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        }

        fun driveId(url: String): String? {
            val host = Uri.parse(url).host?.lowercase() ?: return null
            if (!host.endsWith("drive.google.com") && !host.endsWith("docs.google.com") &&
                !host.endsWith("drive.usercontent.google.com")
            ) return null
            return Regex("/d/([A-Za-z0-9_-]{10,})").find(url)?.groupValues?.get(1)
                ?: Regex("[?&]id=([A-Za-z0-9_-]{10,})").find(url)?.groupValues?.get(1)
        }
    }
}

/** Lets NewPipeExtractor make its web requests through OkHttp. */
private class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: Request): Response {
        val data = request.dataToSend()
        val body = when {
            data != null -> data.toRequestBody()
            request.httpMethod() == "POST" -> ByteArray(0).toRequestBody()
            else -> null
        }
        val builder = okhttp3.Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", LinkImporter.USER_AGENT)
        for ((name, values) in request.headers()) {
            builder.removeHeader(name)
            for (v in values) builder.addHeader(name, v)
        }
        client.newCall(builder.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("reCaptcha challenge requested", request.url())
            return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
        }
    }
}
