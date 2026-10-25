package com.yimidevs.clipai

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val view = LocalView.current
    SideEffect { view.keepScreenOn = state.working || state.rendering }

    if (showSettings) {
        BackHandler { showSettings = false }
        SettingsScreen(vm, onBack = { showSettings = false })
    } else {
        HomeScreen(state, vm, onOpenSettings = { showSettings = true })
    }
}

// ======================= HOME =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: UiState, vm: MainViewModel, onOpenSettings: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.setVideo(uri)
    }
    val keysReady = remember { vm.settingsStore.load().let { it.stt.isReady && it.llm.isReady } }
    val busy = state.working || state.rendering

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ClipAI", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!keysReady) item {
                InfoCard(
                    title = "First, add your free AI keys",
                    body = "ClipAI uses free AI services: Groq to turn speech into text and Gemini (or Groq/OpenRouter) to pick the best moments. It takes about 2 minutes.",
                    action = "Open settings",
                    onAction = onOpenSettings,
                )
            }

            item {
                SectionCard {
                    val v = state.video
                    if (v == null) {
                        Text("Pick a long video with talking: a podcast, interview, stream or talk.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(v.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Text(
                            "${formatTime(v.durationSec)} · ${v.width}×${v.height}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (v == null) "Choose a video" else "Choose a different video") }
                }
            }

            item { LinkCard(state, vm, busy) }

            if (state.video != null) item { OptionsCard(state, vm, busy) }

            if (state.working) item {
                SectionCard {
                    Text(state.status.ifBlank { "Working…" }, fontWeight = FontWeight.SemiBold)
                    val p = state.progress
                    if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    state.log.forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "Keep the app open. A 1-hour video takes a few minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                }
            }

            state.error?.let { err ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Text(err, modifier = Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            if (state.clips.isNotEmpty()) {
                item { ResultsHeader(state, vm) }
                items(state.clips, key = { it.clip.id }) { ClipCard(it, state, vm) }
                item { Spacer(Modifier.padding(24.dp)) }
            }
        }
    }
}

@Composable
private fun LinkCard(state: UiState, vm: MainViewModel, busy: Boolean) {
    val context = LocalContext.current
    var link by rememberSaveable { mutableStateOf("") }
    val o = state.options
    SectionCard {
        Text("Or use a link", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("YouTube or Google Drive link") },
            singleLine = true,
            enabled = !busy,
            trailingIcon = {
                TextButton(onClick = { link = clipboardText(context) }, enabled = !busy) { Text("Paste") }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Text("YouTube quality", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(480, 720, 1080).forEach { h ->
                FilterChip(
                    selected = o.downloadMaxHeight == h,
                    onClick = { vm.setOptions(o.copy(downloadMaxHeight = h)) },
                    label = { Text("${h}p") },
                    enabled = !busy,
                )
            }
        }
        Hint("Data used for a 1-hour video: about 400 MB at 480p, 700 MB at 720p, 1.3 GB at 1080p. Drive files download at their original size.")
        Button(
            onClick = { vm.importLink(link) },
            enabled = !busy && link.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Download video") }
        Hint("Tip: in the YouTube app, tap Share → ClipAI to send a link here directly. Drive files must be shared as \"Anyone with the link\".")
    }
}

@Composable
private fun OptionsCard(state: UiState, vm: MainViewModel, busy: Boolean) {
    val o = state.options
    SectionCard {
        Text("Clip settings", style = MaterialTheme.typography.titleMedium)

        Text("Number of clips: ${o.count}")
        Slider(
            value = o.count.toFloat(),
            onValueChange = { vm.setOptions(o.copy(count = it.roundToInt())) },
            valueRange = 1f..10f, steps = 8, enabled = !busy,
        )

        Text("Shortest clip: ${o.minSec}s")
        Slider(
            value = o.minSec.toFloat(),
            onValueChange = { vm.setOptions(o.copy(minSec = (it / 5).roundToInt() * 5)) },
            valueRange = 10f..120f, steps = 21, enabled = !busy,
        )

        Text("Longest clip: ${o.maxSec}s")
        Slider(
            value = o.maxSec.toFloat(),
            onValueChange = { vm.setOptions(o.copy(maxSec = (it / 5).roundToInt() * 5)) },
            valueRange = 15f..180f, steps = 32, enabled = !busy,
        )

        Text("Frame")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FrameMode.entries.forEach { m ->
                FilterChip(
                    selected = o.frame == m,
                    onClick = { vm.setOptions(o.copy(frame = m)) },
                    label = { Text(m.label) },
                    enabled = !state.rendering,
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Animated captions")
                Text(
                    "Word-by-word, burned into the video",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = o.captions, onCheckedChange = { vm.setOptions(o.copy(captions = it)) }, enabled = !state.rendering)
        }

        Button(onClick = vm::findClips, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.clips.isEmpty()) "Find viral clips" else "Find clips again")
        }
    }
}

@Composable
private fun ResultsHeader(state: UiState, vm: MainViewModel) {
    val pending = state.clips.count { it.selected && it.output == null }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${state.clips.size} clips found",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        if (state.rendering) {
            OutlinedButton(onClick = vm::cancel) { Text("Stop") }
        } else {
            Button(onClick = vm::renderSelected, enabled = pending > 0 && !state.working) {
                Text(if (pending > 0) "Render $pending" else "All rendered")
            }
        }
    }
}

@Composable
private fun ClipCard(ui: ClipUi, state: UiState, vm: MainViewModel) {
    val c = ui.clip
    val context = LocalContext.current
    val locked = state.rendering || state.working

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = ui.selected, onCheckedChange = { vm.toggle(c.id) }, enabled = !locked)
            Column(Modifier.weight(1f)) {
                Text(c.title, fontWeight = FontWeight.Bold)
                Text(
                    "${formatTime(c.start)} – ${formatTime(c.end)} · ${c.duration.roundToInt()}s",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer) {
                Text(
                    "${c.score}",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        if (c.hook.isNotBlank()) Text("“${c.hook}”", fontStyle = FontStyle.Italic)
        if (c.reason.isNotBlank()) {
            Text(c.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Start", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { vm.nudge(c.id, -1.0, 0.0) }, enabled = !locked) { Text("−1s") }
            TextButton(onClick = { vm.nudge(c.id, 1.0, 0.0) }, enabled = !locked) { Text("+1s") }
            Spacer(Modifier.width(8.dp))
            Text("End", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { vm.nudge(c.id, 0.0, -1.0) }, enabled = !locked) { Text("−1s") }
            TextButton(onClick = { vm.nudge(c.id, 0.0, 1.0) }, enabled = !locked) { Text("+1s") }
        }

        ui.renderProgress?.let { p ->
            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Rendering… $p%", style = MaterialTheme.typography.bodySmall)
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        ui.output?.let { file ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openVideo(context, file) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text("Play")
                }
                OutlinedButton(
                    onClick = { vm.saveToGallery(c.id) },
                    enabled = !ui.saved,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(4.dp),
                ) { Text(if (ui.saved) "Saved" else "Save") }
                Button(onClick = { shareVideo(context, file, postCaption(c)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) {
                    Text("Share")
                }
            }
            TextButton(onClick = { copyText(context, postCaption(c)) }) { Text("Copy post caption + hashtags") }
        }
    }
}

// ======================= SETTINGS =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val store = vm.settingsStore
    var s by remember { mutableStateOf(store.load()) }
    fun set(n: AppSettings) {
        s = n
        store.save(n)
    }

    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var modelMenu by remember { mutableStateOf(false) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var chunkText by remember(s.preset) { mutableStateOf(s.chunkChars.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Speech to text ----
            Text("1 · Speech-to-text", style = MaterialTheme.typography.titleMedium)
            Hint("Uses Groq's free Whisper model to write down everything said in the video, with timestamps.")
            TextButton(onClick = { uriHandler.openUri("https://console.groq.com/keys") }) { Text("Get a free Groq key →") }
            KeyField("Groq API key", s.sttKey) { set(s.copy(sttKey = it)) }
            OutlinedTextField(
                value = s.sttModel, onValueChange = { set(s.copy(sttModel = it)) },
                label = { Text("Whisper model") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = s.sttBaseUrl, onValueChange = { set(s.copy(sttBaseUrl = it)) },
                label = { Text("Speech-to-text API URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()

            // ---- Clip-finding AI ----
            Text("2 · Clip-finding AI", style = MaterialTheme.typography.titleMedium)
            Hint("Reads the transcript and picks the moments most likely to go viral.")
            LlmPreset.entries.forEach { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (p != s.preset) {
                            s = store.switchPreset(s, p)
                            models = emptyList()
                            modelError = null
                        }
                    },
                ) {
                    RadioButton(selected = s.preset == p, onClick = null)
                    Text(p.label, modifier = Modifier.padding(start = 8.dp))
                }
            }
            if (s.preset.keyUrl.isNotEmpty()) {
                TextButton(onClick = { uriHandler.openUri(s.preset.keyUrl) }) { Text("Get a free key →") }
            }
            KeyField(
                if (s.preset == LlmPreset.GROQ) "API key (blank = reuse the Groq key above)" else "API key",
                s.llmKey,
            ) { set(s.copy(llmKey = it)) }

            Box {
                OutlinedTextField(
                    value = s.llmModel,
                    onValueChange = { set(s.copy(llmModel = it)) },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (loadingModels) {
                            CircularProgressIndicator(modifier = Modifier.padding(12.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = {
                                loadingModels = true
                                modelError = null
                                scope.launch {
                                    try {
                                        models = vm.loadModels(s)
                                        modelMenu = models.isNotEmpty()
                                        if (models.isEmpty()) modelError = "No models found for this key."
                                    } catch (e: Exception) {
                                        modelError = e.message
                                    } finally {
                                        loadingModels = false
                                    }
                                }
                            }) { Icon(Icons.Filled.Refresh, contentDescription = "Load models") }
                        }
                    },
                )
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    models.forEach { id ->
                        DropdownMenuItem(text = { Text(id) }, onClick = {
                            set(s.copy(llmModel = id))
                            modelMenu = false
                        })
                    }
                }
            }
            Hint("Tap ↻ to load the models your key can use. Free model names change often, so this is the easy way to stay current.")
            modelError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            OutlinedTextField(
                value = s.llmBaseUrl, onValueChange = { set(s.copy(llmBaseUrl = it)) },
                label = { Text("API URL (OpenAI-compatible)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = chunkText,
                onValueChange = { v ->
                    chunkText = v.filter { it.isDigit() }.take(7)
                    chunkText.toIntOrNull()?.takeIf { it >= 2000 }?.let { set(s.copy(chunkChars = it)) }
                },
                label = { Text("Transcript characters per request") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Hint("Lower this if you get 'request too large' errors (Groq's free tier needs about 12,000).")

            HorizontalDivider()

            Button(
                onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = try { vm.testConnections(s) } finally { testing = false }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (testing) "Testing…" else "Test both connections") }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Hint(
                "Keys are stored only on this phone. Audio is sent to the speech-to-text provider and the transcript to the clip AI. " +
                    "Free tiers may use your data to improve their models, so don't clip private recordings.",
            )
            Spacer(Modifier.padding(16.dp))
        }
    }
}

// ======================= small pieces =======================

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun InfoCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(body, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        modifier = Modifier.fillMaxWidth(),
    )
}
