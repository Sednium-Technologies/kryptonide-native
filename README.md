# KryptonIDE

<p align="center">
  <strong>The Next-Generation Native Mobile IDE & Autonomous On-Device AI Development Environment for Android</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android_8.0+_(API_26--35)-brightgreen.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Language-Kotlin_2.0_%7C_C++20-blue.svg" alt="Language" />
  <img src="https://img.shields.io/badge/UI-Jetpack_Compose_120Hz-orange.svg" alt="UI" />
  <img src="https://img.shields.io/badge/Local_AI-MiniCPM_1B_GGUF-purple.svg" alt="Local AI" />
  <img src="https://img.shields.io/badge/License-MIT-green.svg" alt="License" />
</p>

---

> [!IMPORTANT]
> **Continuous Support & Patch Roadmap:**
> More patch updates will come. If you find issues, send it from the issues tab, and we will fix that.

---

## 🌟 Executive Overview

**KryptonIDE** transforms your Android mobile device into a high-performance workstation for professional software development. Built from the ground up natively in Kotlin and Jetpack Compose, KryptonIDE bypasses web-view compromises to deliver **desktop-grade editing speed**, **100% on-device local AI assistance**, **native GitHub integration with live CI/CD tracking**, and **hardware-accelerated 120Hz touch responsiveness**.

Whether building full-stack web applications, debugging complex codebases, reviewing visual Git diffs, or engineering software completely offline during travel, KryptonIDE offers an uncompromising developer experience in the palm of your hand.

---

## ✨ Key Capabilities & Highlights

### ⚡ 1. Native Hardware-Accelerated Editor Surface
- **Sora & Monaco Editor Core**: Direct Android Canvas drawing without WebView latency or layout drift.
- **Monospace Typography**: Bundled with JetBrains Mono, Fira Code, and customizable font support with zero font measurement desynchronization.
- **Developer Ergonomics**:
  - Precision caret navigation and touch-selection teardrops.
  - Quick-Symbol shortcut bar with customizable brackets, quotes, and punctuation.
  - Bracket matching, multi-level code folding, and indentation guides.
  - Emmet abbreviation expansion for lightning-fast HTML/CSS generation.
  - Floating command palette for instant keyboard-free action execution.

### 🧠 2. Pocket Server & 100% On-Device AI
- **MiniCPM 1B Unified Base Brain**: Pre-configured on-device GGUF engine running directly in RAM via native `libllama_server.so` (C++). Zero cloud tokens needed and zero lines of code leave your device.
- **Claude Opus Distilled Reasoning**: Capable of deep internal reflection (`<think>`), structured multi-file planning (`<plan>`), and surgical code modification (`<edit>`) with zero placeholders.
- **Curated Model Catalog**: Categorized library of on-device models with dynamic RAM safety checks and OOM risk detection:
  - *Default Core Engine*: MiniCPM 1B, MiniCPM 2.4B
  - *Code Intelligence & Agents*: Qwen 2.5 Coder 1.5B, Qwen 2.5 Coder 0.5B, DeepSeek R1 Distill
  - *General Reasoning*: SmolLM2 1.7B, Gemma 2 2B
  - *Semantic Embeddings*: BGE Micro, MiniLM
- **Custom Hugging Face Downloader**: Built-in parser for any Hugging Face repo ID or direct `.gguf` link, featuring real-time size computation, memory risk evaluation, and background resume/pause/stop downloading.
- **Cloud BYOK Multi-Provider Cascade**: Seamless fallback to cloud intelligence (Anthropic Claude 3.5 Sonnet, OpenAI GPT-4o, Groq, Google Gemini) via Bring-Your-Own-Key.

### 🐙 3. GitHub Hub, Visual Diffs & Actions Tracker
- **Visual File Diffs**: Interactive in-app side-by-side and unified diff viewer highlighting exact additions, modifications, and deletions with line numbers.
- **Persistent GitHub Actions Tracker**: Real-time polling and visualization of workflow runs (`build`, `test`, `deploy`) with instant failure detection.
- **Live Step Logs**: Inspect raw execution logs directly on your phone with automatic failed-step expansion.
- **Send-to-AI Diagnostics**: One-tap handoff from failed CI/CD workflow logs directly to the AI Assistant for instant diagnosis and automated code fixes.
- **Branch Management**: Switch branches, commit with customizable messages, and push directly to remote repositories.
- **In-App Issue Tracker**: Browse and submit project issues without leaving your development workspace.

### 🌐 4. Live Render & Beam Mirroring
- **Real-Time Preview**: Built-in rendering engine for HTML5, CSS3, and JavaScript web projects.
- **Console & Diagnostics**: Live DOM console log inspection and syntax error extraction.
- **Krypton Beam**: Mirror and test your live project in real time across any browser on your local network.

### 💻 5. Integrated Linux Terminal & Workspaces
- **Local Shell**: Built-in terminal session for executing build commands, running node/python scripts, and managing project environments.
- **Deep File Explorer**: Intuitive gesture-driven file tree with multi-file creation, folder nesting, and drag-and-drop operations.
- **Project Starters**: Instant templating for Web (Vanilla, React, Vue), Node.js, Python, and Kotlin.

### 🧩 6. Extensible Customization & Plugins
- Built-in Plugin Manager supporting Language Server Protocol (LSP), automated code formatters (Prettier, Clang-Format), linters (ESLint, Ktlint), keymaps, and high-contrast color themes.

---

## 🏗️ Technical Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        KryptonIDE User Experience                      │
├────────────────────────────────────────────────────────────────────────┤
│  120Hz Jetpack Compose UI  │  Sora Hardware Canvas  │  Live Web View   │
├────────────────────────────┴────────────────────────┴──────────────────┤
│                  Core IDE Logic & ViewModel Layer (Kotlin)             │
│  - EditorViewModel          - GitViewModel          - AiViewModel      │
│  - PluginManager            - SettingsStore         - BeamServer       │
├────────────────────────────────────────────────────────────────────────┤
│                       Autonomous Agent & AI Subsystem                  │
│  - NeedleAgentCoordinator   - MiniCpmToolParser     - RepetitionGuard  │
│  - PocketModels Catalog     - HuggingFace Downloader- CheckpointManager│
├────────────────────────────────────────────────────────────────────────┤
│                     Native Performance Engines (C/C++)                 │
│  - libllama_server.so (FP16 KV Cache, GGUF Engine)                     │
│  - liboorty_engine.so (sched_setaffinity Prime-Core Pinning)           │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Getting Started (Building from Source)

### System Requirements
- **Host OS**: Linux, macOS, or Windows 11
- **IDE**: Android Studio Ladybug (2024.2.1+) or newer
- **Android SDK**: API Level 35 (compileSdk 35, minSdk 26)
- **JDK**: OpenJDK 17 or higher
- **Android NDK**: Version 26+ with CMake 3.22+

### Quick Setup

1. **Clone the Repository**
   ```bash
   git clone https://github.com/Sednium-Technologies/kryptonide-native.git
   cd kryptonide-native
   ```

2. **Open in Android Studio**
   Open the root directory in Android Studio. Gradle will automatically sync project dependencies.

3. **Run Unit Tests**
   ```bash
   ./gradlew testDebugUnitTest
   ```

4. **Assemble Release APK**
   ```bash
   ./gradlew assembleRelease
   ```
   The optimized APK will be generated at:
   `app/build/outputs/apk/release/app-release.apk`

---

## 💬 Community, Support & Feedback

We are committed to delivering the most reliable mobile coding experience. 

- **Found a bug or have a suggestion?** Please open an issue via our [GitHub Issues](https://github.com/Sednium-Technologies/kryptonide-native/issues) tab.
- **Direct in-app reporting:** You can also view and submit issues directly within KryptonIDE under the GitHub tab.
- We actively triage user feedback and release continuous patch updates.

---

## 📄 License

This project is licensed under the **MIT License**. See the [LICENSE](LICENSE) file for full legal terms.

```
Copyright (c) 2026 Sednium Technologies
```
