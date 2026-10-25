package com.yimidevs.clipai

import android.net.Uri

data class Word(val text: String, val start: Double, val end: Double)

data class Segment(val text: String, val start: Double, val end: Double)

data class Transcript(val segments: List<Segment>, val words: List<Word>)

data class ClipCandidate(
    val id: Int,
    val start: Double,
    val end: Double,
    val title: String,
    val hook: String,
    val reason: String,
    val score: Int,
    val hashtags: List<String>,
) {
    val duration: Double get() = end - start
}

enum class FrameMode(val label: String) {
    VERTICAL_CROP("9:16 crop"),
    VERTICAL_FIT("9:16 fit"),
    ORIGINAL("Original"),
}

data class ClipOptions(
    val count: Int = 5,
    val minSec: Int = 20,
    val maxSec: Int = 60,
    val frame: FrameMode = FrameMode.VERTICAL_CROP,
    val captions: Boolean = true,
    val downloadMaxHeight: Int = 720, // max quality for YouTube downloads
)

/** width/height are the size as displayed (rotation already applied). */
data class VideoInfo(
    val uri: Uri,
    val name: String,
    val durationSec: Double,
    val width: Int,
    val height: Int,
)

fun formatTime(sec: Double): String {
    val s = sec.coerceAtLeast(0.0).toInt()
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun postCaption(c: ClipCandidate): String =
    listOf(c.title, c.hook, c.hashtags.joinToString(" "))
        .filter { it.isNotBlank() }
        .joinToString("\n\n")
