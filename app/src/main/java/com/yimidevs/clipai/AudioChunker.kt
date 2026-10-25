package com.yimidevs.clipai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

class NoAudioException : Exception("This video has no audio track, so there's nothing to transcribe.")

/**
 * Copies the video's audio (no re-encoding, so it's fast) into ~10-minute .m4a pieces.
 * Small pieces stay under the free Whisper upload limit even for multi-hour podcasts.
 */
object AudioChunker {

    data class Chunk(val file: File, val startSec: Double)

    fun split(context: Context, uri: Uri, outDir: File, chunkSec: Int = 600): List<Chunk> {
        outDir.mkdirs()
        val extractor = MediaExtractor()
        val chunks = mutableListOf<Chunk>()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw NoAudioException()

            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val maxInput = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else 0
            val buffer = ByteBuffer.allocateDirect(maxOf(maxInput, 512 * 1024))
            val info = MediaCodec.BufferInfo()
            val chunkUs = chunkSec * 1_000_000L
            var muxTrack = -1
            var chunkIndex = Long.MIN_VALUE
            var chunkFirstUs = 0L

            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val t = extractor.sampleTime
                val idx = t / chunkUs
                if (idx != chunkIndex) {
                    muxer?.stop()
                    muxer?.release()
                    muxer = null
                    val file = File(outDir, "chunk_${chunks.size}.m4a")
                    val m = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxer = m
                    muxTrack = m.addTrack(format)
                    m.start()
                    chunkIndex = idx
                    chunkFirstUs = t
                    chunks.add(Chunk(file, t / 1_000_000.0))
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = t - chunkFirstUs
                info.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else 0
                muxer!!.writeSampleData(muxTrack, buffer, info)
                extractor.advance()
            }
            muxer?.stop()
            muxer?.release()
            muxer = null
            if (chunks.isEmpty()) throw NoAudioException()
            return chunks
        } catch (e: Exception) {
            runCatching { muxer?.release() }
            throw e
        } finally {
            extractor.release()
        }
    }
}
