# Kryptonide

<p align="center">
  <strong>A native Android IDE with an on-device AI workflow.</strong>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Android-API%2026%2B-3DDC84?logo=android&logoColor=white" alt="Android API 26+" />
  <img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Native-4285F4?logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Editor-Sora-222222" alt="Sora Editor" />
  <img src="https://img.shields.io/badge/License-MIT-2EA44F" alt="MIT License" />
</p>

---

Kryptonide turns an Android device into a portable development workspace. Built natively with Kotlin, Jetpack Compose, and the [Sora Editor](https://github.com/Rosemoe/sora-editor), it brings together code editing, project management, Git/GitHub workflows, a built-in shell, web previews, and AI-powered development assistance.

> [!NOTE]
> **Project Status:** Actively developed. Some features depend on device architecture, installed command-line tools, storage permissions, network access, or third-party API providers.  
> More patch updates will come. If you find issues, submit them via the [GitHub Issues](https://github.com/Sednium-Technologies/kryptonide-native/issues) tab or in-app, and we will address them.

---

## What is Kryptonide?

Kryptonide is designed for writing, navigating, running, and maintaining software projects directly from Android.

Its native interface is powered by Kotlin and Android APIs rather than a browser-based IDE shell. The application combines a native code editor with an extensible development workspace and an AI layer supporting both local models and configured cloud providers.

The goal is simple: make Android a capable development environment without sacrificing the flexibility developers expect from a modern IDE.

---

## Features

### ⚡ Native Code Editing
- **Sora Editor Core:** Direct hardware-accelerated code editing engine.
- **Language-Aware Editing:** Syntax highlighting and tokenization across modern programming languages.
- **Visual Customization:** Editor themes, typography and font customization, and configurable keymaps.
- **Project Navigation:** Fast file and folder tree navigation with gesture controls.
- **Multi-File Workflows:** Multi-tab document management and quick project switching.
- **Productivity Tooling:** In-editor search and replace, code formatting, and code-assistance integrations.

### 🧠 AI-Powered Development
Kryptonide supports two distinct AI workflows:

#### 1. Local On-Device AI
- **GGUF Model Catalog:** Built-in model library with background download and storage management.
- **Local Model Installation:** Support for custom GGUF model files and Hugging Face repositories.
- **Native Inference Engine:** Local inference runner backed by a `llama-server` interface (`libllama_server.so`).
- **Needle Agent Tooling:** Structured tool dispatch and deterministic execution for workspace tasks.

> [!IMPORTANT]
> The current local inference path checks for ARM64 support; availability depends on device architecture and runtime compatibility.

#### 2. Cloud AI
- **Bring-Your-Own-Key (BYOK):** Direct API connectivity to leading AI providers:
  - Google Gemini
  - OpenAI
  - Anthropic Claude
  - DeepSeek
  - Moonshot (Kimi)
  - Groq
- **Flexible Model Selection:** Automatic provider model listings with manual model ID configuration fallback.

#### 3. Agent System
The agent layer provides workspace-oriented tools for supported operations:
- File and project operations (read, write, search, patch).
- Terminal execution tasks.
- GitHub workflows.
- Tool dispatch and structured argument validation.
- Agent Skills and Model Context Protocol (MCP) configuration.

### 🐙 Git and GitHub
- **Native Git Operations:** Core version control powered by JGit.
- **Authentication:** GitHub account sign-in and token authentication.
- **Repository Management:** Repository browsing, cloning, and project linking.
- **CI/CD Visibility:** GitHub Actions workflow status and run tracking.
- **Build-Log Inspection:** Real-time log streaming with failed step expansion.
- **Issue Tracking:** In-app GitHub issue browsing, searching, and submission.

### 💻 Built-in Terminal
Kryptonide includes a built-in shell for common development and project operations:
- File and directory management.
- Git version control commands.
- Text processing and searching (`grep`, `find`, `cat`, etc.).
- Archive and compression management (`tar`, `zip`, `unzip`).
- Network utilities (`ping`, `curl`, `netstat`).
- System diagnostics and environment inspection.
- Integration with external Android shell environments.

> [!NOTE]
> The built-in shell is an Android development shell, not a complete desktop Linux distribution. Commands such as Node.js, Python, package managers, and other toolchains may require compatible tools to be installed in the selected shell environment.

### 🌐 Web Preview and Krypton Beam
- **In-App Web Preview:** Embedded rendering engine for HTML, CSS, and JavaScript projects.
- **Developer Diagnostics:** Live preview console and error inspection.
- **Krypton Beam:** Optional local-network preview server allowing project previews to be viewed on another browser on the same Wi-Fi network.

> [!TIP]
> Use Beam on trusted networks and share its local address only with individuals you intend to grant access to.

### 🔍 Language Intelligence (LSP)
Kryptonide includes a Language Server Protocol (LSP) client and server registry:

| Language | Configured Server |
| :--- | :--- |
| **Kotlin** | Kotlin Language Server |
| **TypeScript / JavaScript** | TypeScript Language Server |
| **Python** | Pyright |
| **JSON** | JSON Language Server |
| **Rust** | rust-analyzer |
| **Go** | gopls |

*Actual language support depends on the corresponding server and runtime being installed and runnable on the device.*

### 🧩 Extensibility & Workspace Backups
- **Customization:** Plugin management, custom color themes, and personalized keymaps.
- **Code Hygiene:** Formatter and linter registries (Prettier, Clang-Format, Ktlint).
- **Extensible Workflows:** Agent Skills and MCP tool configurations.
- **Google Drive Backup:** Workspace backup and restore integration via Google Drive.

---

## Architecture

```
Kryptonide
├── Presentation
│   ├── Jetpack Compose UI
│   ├── Sora Editor host
│   ├── Project dashboard
│   └── File explorer
├── IDE Core
│   ├── Editor and project ViewModels
│   ├── Project repository and storage
│   ├── Git / GitHub integration
│   ├── Terminal and build tooling
│   └── Preview and Krypton Beam
├── AI and Agent Layer
│   ├── Local GGUF model management
│   ├── Local inference runner
│   ├── Cloud provider connections
│   ├── Needle agent and tool dispatch
│   └── Skills and MCP configuration
└── Native Components
    ├── C++ Oorty engine
    └── Android platform APIs
```

---

## Tech Stack

| Technology | Purpose |
| :--- | :--- |
| **Kotlin** | Primary application language |
| **Jetpack Compose** | Declarative native Android UI |
| **Sora Editor** | High-performance native code editing engine |
| **C++ / C++17** | Native performance components (`libllama_server.so`, `liboorty_engine.so`) |
| **CMake** | Native C++ build configuration |
| **JGit** | Pure Java Git implementation |
| **OkHttp** | Resilient networking and streaming |
| **Kotlin Coroutines** | Asynchronous operations and structured concurrency |
| **Room / Android Storage APIs** | Application data persistence and project storage |
| **Gradle** | Build system and automation |

---

## System Requirements

| Component | Version / Requirement |
| :--- | :--- |
| **Host OS** | Linux, macOS, or Windows |
| **JDK** | OpenJDK 17 |
| **Android SDK** | Platform 37 (compileSdk 37, targetSdk 37) |
| **Android NDK** | 25.2.9519653 |
| **CMake** | 3.22.1 |
| **Gradle Wrapper** | 9.8.2 |
| **Minimum Android OS** | Android 8.0 (API 26) |

---

## Building from Source

### 1. Clone the Repository
```bash
git clone https://github.com/Sednium-Technologies/kryptonide-native.git
cd kryptonide-native
```

### 2. Configure Local Secrets (Optional)
The repository includes `.env.example` as a template for optional integration values:
```bash
cp .env.example .env
```
Replace placeholders with your own values when required. Never commit API keys, OAuth credentials, signing files, or passwords to version control. Cloud AI providers can also be configured directly through the in-app BYOK settings.

### 3. Open in Android Studio
Open the repository root in Android Studio Ladybug (2024.2.1+) or newer and allow Gradle to sync. Install the required SDK, NDK, and CMake versions if prompted.

### 4. Build a Debug APK
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### 5. Run Tests and Lint
```bash
./gradlew testDebugUnitTest
./gradlew lintDebug
```
To force tests to re-execute from scratch:
```bash
./gradlew testDebugUnitTest --rerun-tasks
```

### 6. Build a Signed Release APK
Release signing is configured via `local.properties`:
```properties
SIGNING_STORE_FILE=/absolute/path/to/your-release-key.p12
SIGNING_STORE_PASSWORD=your-keystore-password
SIGNING_KEY_ALIAS=your-key-alias
SIGNING_KEY_PASSWORD=your-key-password
```
Run:
```bash
./gradlew assembleRelease
```
Output: `app/build/outputs/apk/release/app-release.apk`

---

## Repository Structure

```
.
├── app/
│   └── src/
│       ├── main/
│       │   ├── cpp/               # Native C++ engines and CMake configurations
│       │   ├── java/              # Kotlin application source code
│       │   ├── assets/            # Fonts, templates, and textmate grammars
│       │   └── AndroidManifest.xml
│       └── test/                  # Unit and Robolectric tests
├── docs/                          # Architecture specifications and documentation
├── gradle/                        # Gradle wrapper and version catalog
├── .env.example                   # Environment configuration template
├── build.gradle.kts               # Root build script
├── settings.gradle.kts            # Project settings and plugin repositories
└── README.md
```

---

## Troubleshooting

| Problem | What to Check |
| :--- | :--- |
| **Gradle sync fails** | Verify JDK 17, Android SDK Platform 37, NDK 25.2.9519653, and CMake 3.22.1 installation. |
| **Release signing fails** | Check `SIGNING_STORE_FILE` path and credentials inside `local.properties`. |
| **Local model unavailable** | Check device ABI (ARM64 required), model download completion, available RAM, and runtime compatibility. |
| **Terminal command missing** | Ensure required binary or utility is installed in the selected shell environment. |
| **LSP server unavailable** | Check that the language server executable and its runtime dependencies are installed and runnable. |
| **GitHub operation fails** | Verify personal access token validity, network connectivity, and repository access permissions. |

When reporting an issue, please include your Android version, device model and architecture, reproduction steps, expected behavior, actual behavior, and relevant logs. Ensure all personal tokens and private project data are removed before sharing logs.

---

## Author

**Loid aka Ankush Das**  
Creator and developer of Kryptonide.  
- 🌐 Website: [loid.sednium.com](https://loid.sednium.com)  
- 🏢 Developed under **Sednium Technologies**.

---

## Support & Contributions

- **Found a bug or have a suggestion?** Open an issue via our [GitHub Issues](https://github.com/Sednium-Technologies/kryptonide-native/issues) tracker.
- **Browse the code:** [Sednium-Technologies/kryptonide-native](https://github.com/Sednium-Technologies/kryptonide-native)
- Additional documentation is available in the `docs/` directory.

Contributions, suggestions, and constructive feedback are warmly welcome.

---

## License

Kryptonide is distributed under the **MIT License**. See the [LICENSE](LICENSE) file for complete details.

```
Copyright (c) 2026 Ankush Das.
```
