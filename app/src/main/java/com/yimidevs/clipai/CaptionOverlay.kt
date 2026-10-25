package com.yimidevs.clipai

import android.graphics.Color
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay

/**
 * Burned-in "karaoke" captions: 1-3 words at a time, in bold caps,
 * with the word being spoken highlighted in green.
 */
@UnstableApi
class CaptionOverlay(
    words: List<Word>,
    private val clipStartSec: Double,
    private val textSizePx: Int,
    anchorY: Float,
) : TextOverlay() {

    private val groups: List<List<Word>> = groupWords(words)
    private var firstFrameUs = Long.MIN_VALUE
    private val settings: OverlaySettings =
        StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0f, anchorY).build()

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings

    override fun getText(presentationTimeUs: Long): SpannableString {
        // The first frame we see is the clip's start, whatever timestamp base the pipeline uses.
        if (firstFrameUs == Long.MIN_VALUE) firstFrameUs = presentationTimeUs
        val t = clipStartSec + (presentationTimeUs - firstFrameUs) / 1_000_000.0

        val gi = groups.indexOfFirst { t >= it.first().start - 0.1 && t < it.last().end + 0.2 }
        if (gi < 0) return blank // never empty: an empty bitmap would crash
        val group = groups[gi]
        var active = -1
        for ((i, w) in group.withIndex()) {
            val next = group.getOrNull(i + 1)?.start ?: (w.end + 0.2)
            if (t >= w.start && t < next) active = i
        }
        // Return the same object while nothing changes, so the overlay bitmap isn't redrawn every frame.
        val key = gi * 16 + (active + 1)
        return cache.getOrPut(key) { buildText(group, active) }
    }

    private val blank = SpannableString(" ")
    private val cache = HashMap<Int, SpannableString>()

    private fun buildText(group: List<Word>, active: Int): SpannableString {
        val texts = group.map { it.text.trim().uppercase() }
        val s = SpannableString(" " + texts.joinToString(" ") + " ")
        val len = s.length
        val flag = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        s.setSpan(AbsoluteSizeSpan(textSizePx), 0, len, flag)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, len, flag)
        s.setSpan(ForegroundColorSpan(Color.WHITE), 0, len, flag)
        s.setSpan(BackgroundColorSpan(0xB3000000.toInt()), 0, len, flag)

        var pos = 1
        for (i in texts.indices) {
            val wl = texts[i].length
            if (i == active) s.setSpan(ForegroundColorSpan(HIGHLIGHT), pos, pos + wl, flag)
            pos += wl + 1
        }
        return s
    }

    companion object {
        private const val HIGHLIGHT = 0xFF22C55E.toInt()

        fun groupWords(words: List<Word>): List<List<Word>> {
            val groups = mutableListOf<List<Word>>()
            var cur = mutableListOf<Word>()
            var chars = 0
            for (w in words.sortedBy { it.start }) {
                val txt = w.text.trim()
                if (txt.isEmpty()) continue
                val gap = cur.lastOrNull()?.let { w.start - it.end } ?: 0.0
                if (cur.isNotEmpty() && (cur.size >= 3 || chars + txt.length > 16 || gap > 0.6)) {
                    groups.add(cur)
                    cur = mutableListOf()
                    chars = 0
                }
                cur.add(w)
                chars += txt.length + 1
            }
            if (cur.isNotEmpty()) groups.add(cur)
            return groups
        }
    }
}
