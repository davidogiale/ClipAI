# ClipAI — AI clipping app for Android

Turn a long video (podcast, interview, stream, talk) into short vertical clips for TikTok, Reels and Shorts.

**What it does**
1. You pick a video from your phone, **paste a YouTube or Google Drive link** (or a direct .mp4 link), or tap *Share → ClipAI* from Gallery, Files or the YouTube app.
2. It pulls out the audio and sends it to a **free Whisper** model (Groq) → full transcript with word timings.
3. A **free AI model** (Gemini, Groq or OpenRouter) reads the transcript and picks the moments most likely to go viral, with a title, hook, reason, score and hashtags.
4. On your phone, it cuts each clip, reframes it to **9:16 (1080×1920)** and burns in **animated word-by-word captions** (current word highlighted in green).
5. Play, nudge start/end by ±1 s, save to *Movies/ClipAI*, share straight to TikTok/IG, or copy the caption + hashtags.

Video cutting and captions run on the phone (Media3 Transformer, hardware encoder), so no video is uploaded. Only the audio goes to the speech-to-text API and the text goes to the clip AI.

---

## 1. Get the APK

### Option A — No computer needed (GitHub builds it for you)
1. Create a new **empty** repository on github.com (private is fine).
2. Upload everything in this folder to it (on the repo page: *Add file → Upload files*; keep the folder structure, including `.github/workflows/build-apk.yml`).
3. Open the **Actions** tab. The *Build APK* workflow runs automatically (or press *Run workflow*). It takes about 5–8 minutes.
4. When it's green, open the run, download **ClipAI-apk** at the bottom, unzip it and install `app-debug.apk` on your phone (allow "install unknown apps" when asked).

### Option B — Android Studio
Open this folder in Android Studio (Ladybug or newer), let Gradle sync, plug in your phone with USB debugging on, and press ▶ Run.

Requirements: Android 10 or newer.

---

## 2. Free AI keys (about 2 minutes)

| Job in the app | Provider | Get the key | Default model | Free limits (Oct 2026) |
|---|---|---|---|---|
| Speech → text | **Groq** | https://console.groq.com/keys | `whisper-large-v3-turbo` | 20 requests/min, 7,200 audio-seconds/hour, 28,800/day (≈ 8 hours of audio a day) |
| Pick the clips (recommended) | **Google Gemini** | https://aistudio.google.com/apikey | `gemini-3.5-flash-lite` | Free tier is Flash/Flash-Lite only; developers report ~500 requests/day for Flash-Lite and ~20/day for full Flash |
| Pick the clips (alt.) | **Groq** | same key as above | `openai/gpt-oss-120b` | 30 requests/min, 1,000/day, but only 8,000 tokens/min, so long videos are sent in small pieces |
| Pick the clips (alt.) | **OpenRouter** | https://openrouter.ai/keys | `openai/gpt-oss-120b:free` | Any model ending in `:free`; slow when busy; the list changes often |

In the app: **Settings → paste the Groq key → pick a clip AI → paste its key → tap ↻ next to *Model* to load what your key can use → Test both connections.**

Tip: free model names change every few months. If you get "model not found", tap ↻ and pick another from the list.

Any other OpenAI-compatible API works too (choose *Custom*), e.g. a local Ollama server or a paid key later.

---

## 3. How a run uses your free quota
- A 1-hour podcast = 6 Whisper requests (10-min audio chunks) and 3,600 audio-seconds of Groq quota. The transcript is kept, so "Find clips again" with different lengths doesn't re-transcribe.
- With Gemini, the whole transcript goes in one request. With Groq as the clip AI, a 1-hour transcript is split into ~5 requests and the app waits automatically when the per-minute limit is hit.

## 4. Things to know
- YouTube links use the open-source [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL-3.0). YouTube changes often; if links stop working, bump its version in `app/build.gradle.kts` to the latest release and push.
- Google Drive links must be shared as "Anyone with the link".
- 9:16 crop keeps the **centre** of the frame. Works well for single-speaker and most podcast shots; for two people sitting far apart, try *9:16 fit*.
- The video needs talking; music-only videos have nothing to transcribe.
- Free tiers may use what you send to improve their models (Google says reviewers may read free-tier prompts outside the EEA/UK). Don't clip private recordings.
- Only clip content you have the rights to, or that the campaign/creator allows you to clip.

## 5. Code map
| File | What it does |
|---|---|
| `AudioChunker.kt` | Copies the audio track into 10-minute `.m4a` pieces (no re-encode) |
| `AiClient.kt` | Whisper transcription, chat completions, model list; auto-retry on 429 |
| `HighlightFinder.kt` | The viral-moment prompt, JSON parsing, snapping to sentences, de-overlapping |
| `CaptionOverlay.kt` | Karaoke-style burned-in captions |
| `VideoEditor.kt` | Media3 Transformer: trim, 9:16 reframe, captions, export MP4 |
| `MainViewModel.kt` | The pipeline and app state |
| `Screens.kt` | Home and Settings UI (Jetpack Compose) |

Ideas for v2: face-tracking crop (ML Kit face detection to move the crop window), caption styles, a background service so it keeps working with the screen off, direct upload to TikTok/YouTube.
