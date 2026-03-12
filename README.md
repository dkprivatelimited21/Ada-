# ADA — Adaptive Digital Assistant
### A Jarvis-level Android AI System

```
    ██████╗ ██████╗  █████╗
   ██╔══██╗██╔══██╗██╔══██╗
   ███████║██║  ██║███████║
   ██╔══██║██║  ██║██╔══██║
   ██║  ██║██████╔╝██║  ██║
   ╚═╝  ╚═╝╚═════╝ ╚═╝  ╚═╝
   Adaptive Digital Assistant
```

---

## Overview

ADA is a privacy-first, offline-capable Android AI assistant inspired by Tony Stark's Jarvis.
She runs on Android 5.0+ (API 21), is built in Kotlin with Clean Architecture, and supports
multiple AI backends including local Ollama models, GGUF models, and OpenAI-compatible APIs.

---

## Project Structure

```
ADA/
├── app/
│   └── src/main/
│       ├── kotlin/com/ada/assistant/
│       │   ├── AdaApplication.kt            ← Hilt app + notification channels
│       │   ├── ai/
│       │   │   ├── engine/
│       │   │   │   └── AiEngine.kt          ← Multi-provider AI abstraction
│       │   │   └── agents/
│       │   │       └── AgentManager.kt      ← All agents + routing
│       │   ├── automation/
│       │   │   ├── AutomationEngine.kt      ← Device control
│       │   │   └── Receivers.kt             ← Boot + Reminder receivers
│       │   ├── data/
│       │   │   ├── local/
│       │   │   │   └── AdaDatabase.kt       ← Room DB + all DAOs
│       │   │   └── models/
│       │   │       └── Models.kt            ← All data models + entities
│       │   ├── di/
│       │   │   └── DatabaseModule.kt        ← Hilt DI providers
│       │   ├── memory/
│       │   │   └── MemoryManager.kt         ← Long-term memory system
│       │   ├── plugins/
│       │   │   └── PluginManager.kt         ← Plugin architecture
│       │   ├── ui/
│       │   │   ├── activities/
│       │   │   │   └── Activities.kt        ← Main, Splash, Settings, Memory
│       │   │   ├── adapters/
│       │   │   │   └── MessageAdapter.kt    ← Chat RecyclerView adapter
│       │   │   └── viewmodels/
│       │   │       └── AdaViewModel.kt      ← Main ViewModel (MVVM)
│       │   └── voice/
│       │       └── VoiceManager.kt          ← STT + TTS + wake word
│       └── res/
│           ├── layout/
│           │   ├── activity_main.xml
│           │   ├── activity_settings.xml
│           │   ├── activity_memory_viewer.xml
│           │   ├── item_message_user.xml
│           │   └── item_message_ada.xml
│           └── values/
│               ├── colors.xml
│               ├── strings.xml
│               └── themes.xml
└── build.gradle
```

---

## Quick Start

### Step 1 — Android Studio Setup

1. Open Android Studio (Hedgehog or newer)
2. `File → New → Import Project` → select the `ADA/` folder
3. Let Gradle sync complete
4. Add these missing resource files (placeholders):

```
res/drawable/
├── ic_mic.xml          (Vector: mic icon)
├── ic_mic_active.xml   (Vector: mic active/red)
├── ic_send.xml         (Vector: send icon)
├── ic_speaker.xml      (Vector: speaker icon)
├── ic_stop.xml         (Vector: stop icon)
├── ic_settings.xml     (Vector: gear icon)
├── ic_memory.xml       (Vector: brain/memory icon)
├── ic_new_chat.xml     (Vector: new chat icon)
├── ic_ada_notification.xml
├── circle_green.xml    (Shape: circle, #00E676)
├── bg_message_user.xml (Shape: rounded rect, color bubble_user)
├── bg_message_ada.xml  (Shape: rounded rect, color bubble_ada)
├── bg_input_field.xml  (Shape: rounded rect, color surface_elevated)
├── bg_voice_btn.xml    (Shape: circle, color ada_cyan)
└── bg_ada_avatar.xml   (Shape: circle, color ada_cyan)

res/font/
├── inter.ttf           (Download: fonts.google.com/specimen/Inter)
└── jetbrains_mono.ttf  (Download: jetbrains.com/legalnotice/fonts)

res/raw/
├── thinking_dots.json  (Lottie: loading animation)
└── voice_waveform.json (Lottie: waveform animation)
```

---

### Step 2 — Connect an AI Backend

#### Option A: Ollama (Recommended for Privacy)

1. Install Ollama on your PC/Mac: https://ollama.ai
2. Pull a model:
   ```bash
   ollama pull llama3.2:3b      # Fastest, ~2GB
   ollama pull phi3:mini         # Very capable, ~2.3GB
   ollama pull mistral:7b        # Best quality, ~4.1GB
   ollama pull tinyllama         # Minimal, ~637MB
   ```
3. Start Ollama server with network access:
   ```bash
   OLLAMA_HOST=0.0.0.0 ollama serve
   ```
4. Find your PC's IP address:
   ```bash
   # Mac/Linux
   ifconfig | grep "inet "
   # Windows
   ipconfig
   ```
5. In ADA Settings:
   - Ollama URL: `http://YOUR_IP:11434/`
   - Model: `llama3.2:3b`

#### Option B: OpenAI API

1. Get API key: https://platform.openai.com
2. In ADA Settings → enter your API key
3. ADA will use `gpt-4o-mini` by default (cost-efficient)

#### Option C: Offline Demo Mode

ADA works without any server using the built-in Mock provider.
Responses are limited but the full UI, memory, and automation systems function.

---

### Step 3 — Build & Install

```bash
# Debug build
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug

# Or use Android Studio → Run button
```

**Minimum requirements:**
- Android 5.0 (API 21)
- 2GB RAM recommended (1GB minimum)
- ~50MB storage for app
- Internet OR local network for AI

---

## Core System Architecture

```
User Input (Text or Voice)
        │
        ▼
    AdaViewModel
        │
        ▼
   AgentManager ──── Intent Extraction (AiEngine)
        │
        ├── MemoryAgent    → manages long-term memory
        ├── AutomationAgent → opens apps, sets reminders
        ├── CodingAgent    → writes/explains code
        ├── ResearchAgent  → answers questions in depth
        └── ConversationAgent → general dialogue (fallback)
        │
        ▼
    AiEngine ──────── OllamaProvider → Local Ollama
                  └── OpenAIProvider → OpenAI API
                  └── MockProvider   → Offline fallback
        │
        ▼
    Memory System (Room DB)
    ├── Short-term: conversation history
    ├── Long-term: persistent Memory table
    ├── User Profile: preferences/habits
    └── Command Log: pattern learning
```

---

## Supported Commands

| Command | Agent | Example |
|---------|-------|---------|
| Open app | Automation | "Ada open Spotify" |
| Set reminder | Automation | "Remind me in 20 minutes" |
| Create note | Automation | "Create a note: buy milk" |
| Open settings | Automation | "Open WiFi settings" |
| Web search | Plugin | "Search for Kotlin coroutines" |
| Calendar event | Plugin | "Schedule meeting tomorrow 3pm" |
| Set timer | Plugin | "Set a 5 minute timer" |
| Code generation | Coding | "Write a bubble sort in Python" |
| Code explanation | Coding | "Explain what this code does" |
| General Q&A | Research | "What is quantum computing?" |
| Remember something | Memory | "Remember that I prefer dark mode" |
| Recall memories | Memory | "What do you know about me?" |
| General chat | Conversation | "How are you today?" |

---

## Memory System

ADA's memory has 4 layers:

1. **Short-term** — conversation context (last 20 messages, in-memory)
2. **Long-term** — persistent Room database with confidence scoring
3. **User Profile** — name, preferences, primary use cases, wake/sleep time
4. **Behavior Learning** — counts command patterns, adapts over time

Memory types stored:
- `PREFERENCE` — "prefers dark themes", "uses Python"
- `FACT` — "name is Alex", "timezone is UTC+5"
- `HABIT` — "frequently asks for code help"
- `GOAL` — "learning machine learning"
- `SKILL` — "experienced in Android development"
- `INSTRUCTION` — "always explain code line by line"

---

## Plugin System

Add new capabilities without touching core code:

```kotlin
class MyPlugin : AdaPlugin {
    override val pluginId = "my_plugin"
    override val pluginName = "My Custom Plugin"
    override val version = "1.0"
    override val description = "Does something cool"
    override val capabilities = listOf("my_intent", "related_intent")

    override suspend fun initialize(context: Context): Boolean = true

    override suspend fun canHandle(intent: String, input: String): Boolean {
        return input.lowercase().contains("my trigger word")
    }

    override suspend fun execute(
        intent: String, input: String, params: Map<String, String>
    ): PluginResult {
        // Your logic here
        return PluginResult(true, "Done!")
    }

    override fun destroy() {}
}

// Register it:
pluginManager.registerPlugin(MyPlugin())
```

Built-in plugins:
- **WebSearchPlugin** — opens Google Search
- **CalendarPlugin** — creates calendar events
- **FileManagerPlugin** — opens file manager
- **TimerPlugin** — sets timers

---

## Voice System

```
User speaks
    → SpeechRecognizer (Android native, offline-capable)
    → Text
    → AgentManager.process()
    → AI response
    → TextToSpeech (Ada's voice)
    → Played back to user
```

Voice settings:
- Pitch: 1.05 (slightly higher, feminine)
- Speech rate: 0.95 (calm, composed)
- Wake word: "Ada" (configurable)

---

## Recommended Models by Device

| Device RAM | Recommended Model | Notes |
|------------|------------------|-------|
| 1-2GB | TinyLlama (637MB) | Basic chat only |
| 3-4GB | Phi-3 Mini (2.3GB) | Good balance |
| 4-6GB | Llama 3.2 3B (2GB) | Best for most |
| 6GB+ | Mistral 7B (4.1GB) | Best quality |

---

## Future Expansion Roadmap

### Near-term
- [ ] Persistent notification with quick-access mic button
- [ ] Widget for home screen
- [ ] Conversation export to markdown
- [ ] Dark/light theme toggle
- [ ] Conversation search

### Mid-term
- [ ] **Vector memory** — semantic search with FAISS or SQLite-vec
- [ ] **Screen understanding** — Accessibility Service for UI context
- [ ] **On-device models** — llama.cpp JNI binding, no server needed
- [ ] **Personal knowledge graph** — Neo4j-style entity relationships
- [ ] **Scheduled automation** — recurring tasks with WorkManager

### Advanced
- [ ] **Autonomous agents** — multi-step task completion without user prompts
- [ ] **Camera vision** — describe scenes, read text in photos
- [ ] **Self-improving prompts** — Ada refines her own system prompt over time
- [ ] **Plugin marketplace** — install plugins from URL
- [ ] **Smart home bridge** — Home Assistant / MQTT integration
- [ ] **Proactive assistance** — Ada initiates based on patterns (e.g., "You have a meeting in 10 minutes")

---

## Privacy

ADA is designed privacy-first:

- All conversation data stored locally in Room database
- No telemetry or analytics
- AI processing happens on your local network (Ollama) or via API of your choice
- Memory system is fully user-viewable and deletable
- No cloud sync unless you explicitly configure it

---

## Troubleshooting

**"ADA is not responding"**
- Check Ollama server is running: `http://YOUR_IP:11434` in browser
- Verify phone and PC are on same WiFi network
- Try demo mode first to confirm app works

**Voice not working**
- Grant microphone permission in Android Settings → Apps → ADA
- Test with text input first

**Slow responses**
- Switch to a smaller model (tinyllama, phi3:mini)
- Reduce `maxTokens` in GenerationOptions

**Build errors**
- Make sure to add font files to `res/font/`
- Add placeholder drawable XMLs for all `ic_*` references
- Lottie raw files can be downloaded from lottiefiles.com

---

## License

MIT License — Build freely, extend endlessly.

---

*ADA — Calm. Intelligent. Yours.*
