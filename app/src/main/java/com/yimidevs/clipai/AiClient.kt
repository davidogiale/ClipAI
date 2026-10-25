package com.yimidevs.clipai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : Exception(message)

/** One client for every OpenAI-compatible provider (Groq, Gemini, OpenRouter, ...). */
class AiClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .build()

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // ---------- Speech to text (Whisper) ----------

    suspend fun transcribe(
        cfg: ProviderConfig,
        file: File,
        offsetSec: Double,
        onWait: (String) -> Unit = {},
    ): Transcript {
        val build = {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", cfg.model)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("timestamp_granularities[]", "word")
                .addFormDataPart("file", file.name, file.asRequestBody("audio/mp4".toMediaType()))
                .build()
            Request.Builder()
                .url(endpoint(cfg.baseUrl, "audio/transcriptions"))
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .post(body)
                .build()
        }
        val text = send(build, onWait)
        return parseTranscript(JSONObject(text), offsetSec)
    }

    private fun parseTranscript(json: JSONObject, offset: Double): Transcript {
        val segments = mutableListOf<Segment>()
        json.optJSONArray("segments")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text").trim()
                if (t.isEmpty()) continue
                segments.add(Segment(t, o.optDouble("start", 0.0) + offset, o.optDouble("end", 0.0) + offset))
            }
        }
        if (segments.isEmpty()) {
            val t = json.optString("text").trim()
            if (t.isNotEmpty()) segments.add(Segment(t, offset, offset + json.optDouble("duration", 30.0)))
        }
        val words = mutableListOf<Word>()
        json.optJSONArray("words")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val w = o.optString("word").trim()
                if (w.isEmpty()) continue
                words.add(Word(w, o.optDouble("start", 0.0) + offset, o.optDouble("end", 0.0) + offset))
            }
        }
        return Transcript(segments, words.ifEmpty { synthesizeWords(segments) })
    }

    /** If a provider gives no word timings, spread each segment's words evenly over it. */
    private fun synthesizeWords(segments: List<Segment>): List<Word> {
        val out = mutableListOf<Word>()
        for (s in segments) {
            val parts = s.text.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isEmpty()) continue
            val step = (s.end - s.start).coerceAtLeast(0.1) / parts.size
            parts.forEachIndexed { i, p -> out.add(Word(p, s.start + i * step, s.start + (i + 1) * step)) }
        }
        return out
    }

    // ---------- Chat (the "brain" that picks clips) ----------

    suspend fun chat(
        cfg: ProviderConfig,
        system: String,
        user: String,
        onWait: (String) -> Unit = {},
    ): String {
        fun request(jsonMode: Boolean): Request {
            val payload = JSONObject()
                .put("model", cfg.model)
                .put("temperature", 0.4)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", system))
                        .put(JSONObject().put("role", "user").put("content", user)),
                )
            if (jsonMode) payload.put("response_format", JSONObject().put("type", "json_object"))
            return Request.Builder()
                .url(endpoint(cfg.baseUrl, "chat/completions"))
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .header("X-Title", "ClipAI")
                .post(payload.toString().toRequestBody(jsonType))
                .build()
        }

        val text = try {
            send({ request(true) }, onWait)
        } catch (e: ApiException) {
            // Some free models don't support JSON mode; ask again without it.
            if (e.code == 400 || e.code == 422) send({ request(false) }, onWait) else throw e
        }
        val obj = JSONObject(text)
        val message = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ApiException(0, "The AI sent back no answer: ${text.take(200)}")
        return message.optString("content")
    }

    suspend fun listModels(cfg: ProviderConfig): List<String> {
        val text = send({
            Request.Builder()
                .url(endpoint(cfg.baseUrl, "models"))
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .get()
                .build()
        })
        val arr = JSONObject(text).optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("id")?.takeIf { id -> id.isNotBlank() } }
    }

    // ---------- plumbing ----------

    private fun endpoint(base: String, path: String) = base.trim().trimEnd('/') + "/" + path

    /** Sends a request, waiting and retrying when a free tier says "slow down" (429) or the server hiccups. */
    private suspend fun send(build: () -> Request, onWait: (String) -> Unit = {}): String =
        withContext(Dispatchers.IO) {
            var attempt = 0
            var result: String? = null
            while (result == null) {
                val response = http.newCall(build()).await()
                val code = response.code
                val ok = response.isSuccessful
                val retryAfter = response.header("retry-after")?.toDoubleOrNull()
                val body = response.body?.string().orEmpty()
                response.close()
                if (ok) {
                    result = body
                } else if ((code == 429 || code >= 500) && attempt < 4) {
                    val waitSec = (retryAfter ?: listOf(8.0, 20.0, 40.0, 60.0)[attempt]).coerceIn(1.0, 90.0)
                    onWait("Free-tier limit or busy server (HTTP $code). Retrying in ${waitSec.toInt()}s…")
                    delay((waitSec * 1000).toLong())
                    attempt++
                } else {
                    throw ApiException(code, describeError(code, body))
                }
            }
            checkNotNull(result)
        }

    private fun describeError(code: Int, body: String): String {
        val apiMessage = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull()?.takeIf { it.isNotBlank() }
        val detail = apiMessage ?: body.take(300)
        val hint = when (code) {
            401, 403 -> "The API key was rejected. Check it in Settings."
            404 -> "Model or URL not found. In Settings, tap the refresh icon next to Model and pick one from the list."
            413 -> "The file is too big for this provider."
            429 -> "Free-tier limit reached. Wait a few minutes or switch provider in Settings."
            else -> "Request failed."
        }
        return "$hint (HTTP $code: $detail)"
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
