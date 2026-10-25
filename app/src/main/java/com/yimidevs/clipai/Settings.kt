package com.yimidevs.clipai

import android.content.Context

/** Any OpenAI-compatible endpoint: base URL + key + model id. */
data class ProviderConfig(val baseUrl: String, val apiKey: String, val model: String) {
    val isReady: Boolean get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/**
 * Free "brains" that pick the clips. All of them speak the OpenAI chat-completions format,
 * so one client works for every provider.
 *
 * chunkChars = how much transcript is sent per request. Groq's free tier allows only a few
 * thousand tokens per minute, so it gets small chunks; Gemini can take a whole podcast at once.
 */
enum class LlmPreset(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyUrl: String,
    val chunkChars: Int,
) {
    GEMINI(
        "Google Gemini (free tier)",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        "gemini-3.5-flash-lite",
        "https://aistudio.google.com/apikey",
        200_000,
    ),
    GROQ(
        "Groq (free tier)",
        "https://api.groq.com/openai/v1",
        "openai/gpt-oss-120b",
        "https://console.groq.com/keys",
        12_000,
    ),
    OPENROUTER(
        "OpenRouter (:free models)",
        "https://openrouter.ai/api/v1",
        "openai/gpt-oss-120b:free",
        "https://openrouter.ai/keys",
        40_000,
    ),
    CUSTOM("Custom (any OpenAI-compatible API)", "", "", "", 30_000),
}

data class AppSettings(
    val sttKey: String,
    val sttModel: String,
    val sttBaseUrl: String,
    val preset: LlmPreset,
    val llmKey: String,
    val llmModel: String,
    val llmBaseUrl: String,
    val chunkChars: Int,
) {
    val stt: ProviderConfig get() = ProviderConfig(sttBaseUrl.trim(), sttKey.trim(), sttModel.trim())

    /** With Groq picked for both jobs, the speech-to-text key is reused if this one is blank. */
    val llm: ProviderConfig
        get() {
            val key = llmKey.trim().ifBlank { if (preset == LlmPreset.GROQ) sttKey.trim() else "" }
            return ProviderConfig(llmBaseUrl.trim(), key, llmModel.trim())
        }
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("clipai_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val preset = runCatching {
            LlmPreset.valueOf(prefs.getString("preset", LlmPreset.GEMINI.name) ?: LlmPreset.GEMINI.name)
        }.getOrDefault(LlmPreset.GEMINI)
        val p = preset.name
        return AppSettings(
            sttKey = prefs.getString("stt_key", "") ?: "",
            sttModel = prefs.getString("stt_model", DEFAULT_STT_MODEL) ?: DEFAULT_STT_MODEL,
            sttBaseUrl = prefs.getString("stt_base", GROQ_BASE) ?: GROQ_BASE,
            preset = preset,
            llmKey = prefs.getString("llm_key_$p", "") ?: "",
            llmModel = prefs.getString("llm_model_$p", preset.defaultModel) ?: preset.defaultModel,
            llmBaseUrl = prefs.getString("llm_base_$p", preset.baseUrl) ?: preset.baseUrl,
            chunkChars = prefs.getInt("chunk_$p", preset.chunkChars),
        )
    }

    fun save(s: AppSettings) {
        val p = s.preset.name
        prefs.edit()
            .putString("stt_key", s.sttKey)
            .putString("stt_model", s.sttModel)
            .putString("stt_base", s.sttBaseUrl)
            .putString("preset", p)
            .putString("llm_key_$p", s.llmKey)
            .putString("llm_model_$p", s.llmModel)
            .putString("llm_base_$p", s.llmBaseUrl)
            .putInt("chunk_$p", s.chunkChars)
            .apply()
    }

    /** Switch provider; each provider remembers its own key and model. */
    fun switchPreset(current: AppSettings, to: LlmPreset): AppSettings {
        save(current)
        prefs.edit().putString("preset", to.name).apply()
        return load()
    }

    companion object {
        const val GROQ_BASE = "https://api.groq.com/openai/v1"
        const val DEFAULT_STT_MODEL = "whisper-large-v3-turbo"
    }
}
