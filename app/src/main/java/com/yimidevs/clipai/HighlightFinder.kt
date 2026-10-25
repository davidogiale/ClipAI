package com.yimidevs.clipai

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Sends the timestamped transcript to the chat model and turns its answer into clean, non-overlapping clips. */
class HighlightFinder(private val ai: AiClient) {

    private data class RawClip(
        val start: Double,
        val end: Double,
        val title: String,
        val hook: String,
        val reason: String,
        val score: Int,
        val hashtags: List<String>,
    )

    suspend fun find(
        cfg: ProviderConfig,
        transcript: Transcript,
        durationSec: Double,
        options: ClipOptions,
        chunkChars: Int,
        onStatus: (String) -> Unit,
    ): List<ClipCandidate> {
        // Split the transcript into windows small enough for the provider's free limits.
        val windows = mutableListOf<String>()
        val sb = StringBuilder()
        for (s in transcript.segments) {
            val line = String.format(Locale.US, "[%.1f-%.1f] %s", s.start, s.end, s.text)
            if (sb.isNotEmpty() && sb.length + line.length + 1 > chunkChars) {
                windows.add(sb.toString())
                sb.clear()
            }
            sb.append(line).append('\n')
        }
        if (sb.isNotEmpty()) windows.add(sb.toString())

        val wantedTotal = options.count + 3 // ask for extras, keep the best
        val perWindow = if (windows.size == 1) wantedTotal
        else max(2, ceil(wantedTotal * 2.0 / windows.size).toInt())

        val raw = mutableListOf<RawClip>()
        windows.forEachIndexed { i, w ->
            onStatus(
                if (windows.size > 1) "AI is reading part ${i + 1} of ${windows.size}…"
                else "AI is picking the best moments…",
            )
            val reply = ai.chat(cfg, SYSTEM_PROMPT, userPrompt(w, durationSec, options, perWindow), onStatus)
            raw.addAll(parse(reply))
        }
        return postProcess(raw, transcript, durationSec, options)
    }

    private fun userPrompt(window: String, durationSec: Double, o: ClipOptions, want: Int) = """
        The full video is ${durationSec.toInt()} seconds long.
        Find up to $want of the best clips in this part of the transcript.
        Each clip must be between ${o.minSec} and ${o.maxSec} seconds long.
        If nothing in this part is strong, return fewer clips.

        Transcript (each line is [start-end] in seconds):
        $window
    """.trimIndent()

    private fun parse(reply: String): List<RawClip> {
        val cleaned = reply.replace("```json", "").replace("```", "").trim()
        val arr: JSONArray = runCatching {
            val obj = JSONObject(cleaned.substring(cleaned.indexOf('{'), cleaned.lastIndexOf('}') + 1))
            obj.optJSONArray("clips") ?: obj.optJSONArray("highlights")
        }.getOrNull() ?: runCatching {
            JSONArray(cleaned.substring(cleaned.indexOf('['), cleaned.lastIndexOf(']') + 1))
        }.getOrNull() ?: return emptyList()

        val out = mutableListOf<RawClip>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val start = parseTime(o.opt("start"))
            val end = parseTime(o.opt("end"))
            if (start.isNaN() || end.isNaN() || end <= start) continue
            val tags = mutableListOf<String>()
            when (val h = o.opt("hashtags")) {
                is JSONArray -> for (j in 0 until h.length()) tags.add(h.optString(j))
                is String -> tags.addAll(h.split(Regex("[\\s,]+")))
            }
            out.add(
                RawClip(
                    start = start,
                    end = end,
                    title = o.optString("title").trim(),
                    hook = o.optString("hook").trim(),
                    reason = o.optString("reason").trim(),
                    score = o.optInt("score", 50),
                    hashtags = tags.map { it.trim() }.filter { it.isNotEmpty() }
                        .map { if (it.startsWith("#")) it else "#$it" }.take(6),
                ),
            )
        }
        return out
    }

    private fun parseTime(v: Any?): Double = when (v) {
        is Number -> v.toDouble()
        is String -> {
            val s = v.trim()
            if (':' in s) s.split(':').fold(0.0) { acc, p -> acc * 60 + (p.trim().toDoubleOrNull() ?: 0.0) }
            else s.toDoubleOrNull() ?: Double.NaN
        }
        else -> Double.NaN
    }

    private fun postProcess(
        raw: List<RawClip>,
        t: Transcript,
        dur: Double,
        o: ClipOptions,
    ): List<ClipCandidate> {
        val segs = t.segments
        val fixed = raw.mapNotNull { r ->
            var s = r.start.coerceIn(0.0, dur)
            var e = r.end.coerceIn(0.0, dur)
            // Snap to sentence edges so clips don't start or end mid-word.
            segs.lastOrNull { it.start <= s + 0.3 }?.let { if (s - it.start < 3.0) s = it.start }
            segs.firstOrNull { it.end >= e - 0.3 }?.let { if (it.end - e < 3.0) e = it.end }
            if (e - s < o.minSec) e = min(dur, s + o.minSec)
            if (e - s > o.maxSec) e = s + o.maxSec
            if (e - s < min(o.minSec.toDouble(), dur) * 0.5) null else r.copy(start = s, end = e)
        }

        val chosen = mutableListOf<RawClip>()
        for (c in fixed.sortedByDescending { it.score }) {
            if (chosen.size >= o.count) break
            val overlaps = chosen.any { x ->
                val overlap = min(x.end, c.end) - max(x.start, c.start)
                overlap > 0.3 * min(x.end - x.start, c.end - c.start)
            }
            if (!overlaps) chosen.add(c)
        }
        return chosen.mapIndexed { i, r ->
            ClipCandidate(
                id = i + 1,
                start = r.start,
                end = r.end,
                title = r.title.ifBlank { "Clip ${i + 1}" },
                hook = r.hook,
                reason = r.reason,
                score = r.score.coerceIn(0, 100),
                hashtags = r.hashtags,
            )
        }
    }

    companion object {
        private val SYSTEM_PROMPT = """
            You are a top short-form video editor. You turn long videos (podcasts, interviews, streams, talks)
            into viral clips for TikTok, Instagram Reels and YouTube Shorts.

            A great clip:
            - opens with a strong hook in the first 3 seconds (bold claim, question, surprising fact, emotion, conflict or punchline)
            - makes sense on its own without the rest of the video
            - has a clear payoff (an answer, the end of a story, a punchline, an insight)
            - starts at the beginning of a sentence and ends at the end of a sentence
            Avoid intros, sponsor reads, greetings, small talk and moments that need earlier context.

            Reply with JSON only, no markdown, in exactly this shape:
            {"clips":[{"start":12.3,"end":48.9,"title":"short catchy title, max 8 words","hook":"the opening line or why viewers stop scrolling","reason":"why this will perform","score":87,"hashtags":["#tag1","#tag2","#tag3"]}]}
            start and end are seconds taken from the transcript timestamps. score is 0-100 viral potential.
        """.trimIndent()
    }
}
