# Editor UI Upgrades, AI Rebranding, Skills/MCP Storage, & Packages Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the editor UI with a modern studio aesthetic (breadcrumbs, tactile shortcuts bar, active pill navigation), rebrand Larry to AI while preserving BYOK, migrate Skills and MCP storage to `/storage/emulated/0/KryptonIDE/` as JSON and MD with no mock MCPs, configure shell execution to escape Android `noexec`/symlink limits, and implement an in-app package installer targeting `/storage/emulated/0/KryptonIDE/packages`.

**Architecture:** 
- UI modernization in `EditorScreen.kt` using Jetpack Compose with tactile haptics, breadcrumbs, and pill navigation.
- Storage layer refactoring in `SkillsRepository.kt` and `McpRepository.kt` to target `/storage/emulated/0/KryptonIDE/` with dual `.md` and `.json` files.
- Process and shell execution in `TerminalViewModel.kt`, `KryptonShellEngine.kt`, and `RenderEngine.kt` inject `NODE_PATH` and `PYTHONPATH` pointing to shared `packages/`, using explicit bash interpreters.
- Package infrastructure in `PackageInstaller.kt` runs `--no-bin-links` for npm and `--target` for pip, shown in an interactive in-app dialog during project creation.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Android SDK (API 34/24), Sora Editor, Termux Bridge, Kotlinx Serialization.

**Spec:** `docs/superpowers/specs/2026-10-01-editor-ui-ai-rebrand-packages-design.md`

## Global Constraints
- Target paths: `/storage/emulated/0/KryptonIDE/skills`, `/storage/emulated/0/KryptonIDE/mcp`, `/storage/emulated/0/KryptonIDE/packages`.
- No mock or default MCP servers in `DEFAULT_MCP_PRESETS`.
- Never execute binaries directly on external storage (`noexec`); use `bash`, `node`, or `python` explicitly.
- Never use symlinks on external storage; use `--no-bin-links` for npm and `--target` for pip.
- Full BYOK support remains intact for Gemini, Claude, OpenAI, DeepSeek, Kimi, Groq, Ollama.
- Final APK must assemble cleanly (`assembleRelease`) and be transferred to device **INI** via KDE Connect.

---

### Task 1: Rebrand Larry to AI & Preserve BYOK

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/LarrySystemPrompt.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/LarryAIPanel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorCommandPalette.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/FileExplorerPanel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorScreen.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/settings/SettingsScreen.kt`

**Interfaces:**
- Bottom nav tab: `"AI"`
- Palette category: `PaletteCategory.AI("AI")`
- File explorer context item: `"Send to AI"`
- Prompt agent name: `"AI"`

- [ ] **Step 1: Update LarrySystemPrompt.kt agent identity**
  - Replace "You are **Larry**" with "You are **AI**, the agentic coding assistant embedded in **KryptonIDE**".
  - Change metadata `- **Name**: Larry` to `- **Name**: AI`.

- [ ] **Step 2: Update LarryAIPanel.kt UI copy**
  - Update top bar title to "AI Assistant".
  - Update empty state header to "AI Assistant" and subtitle to "How can I help you code today?".
  - Update chat input placeholder to "Ask AI or describe code changes...".

- [ ] **Step 3: Update EditorCommandPalette.kt, FileExplorerPanel.kt, and SettingsScreen.kt**
  - Change `PaletteCategory.LARRY("Larry AI")` to `PaletteCategory.AI("AI")`.
  - Update command titles: "Open AI", "Ask AI to explain...", "Ask AI to fix issue", "Generate code with AI", "Review file with AI".
  - In `FileExplorerPanel.kt`, update context menu item to "Send to AI".
  - In `SettingsScreen.kt`, update labels to "AI Configuration" and "AI Models & Keys".

- [ ] **Step 4: Update EditorScreen.kt navigation item**
  - In `EditorBottomNav`, change label from `"Larry"` to `"AI"`.
  - Verify BYOK model providers and keys remain intact and functional.

- [ ] **Step 5: Verify compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL

---

### Task 2: Skills & MCP Storage in `/storage/emulated/0/KryptonIDE/` (JSON & MD, No Mock MCP)

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/data/mcp/McpModels.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/data/mcp/McpRepository.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/data/repository/SkillsRepository.kt`

**Interfaces:**
- `DEFAULT_MCP_PRESETS: List<McpServerConfig> = emptyList()`
- `SkillsRepository.dir`: points to `/storage/emulated/0/KryptonIDE/skills` (with internal fallback)
- `McpRepository.dir`: points to `/storage/emulated/0/KryptonIDE/mcp` (with internal fallback)
- Produces `skills.json` and `<skill>.md` in `skills/`
- Produces `mcp_servers.json` and `mcp_servers.md` in `mcp/`

- [ ] **Step 1: Empty DEFAULT_MCP_PRESETS in McpModels.kt**
  - Set `val DEFAULT_MCP_PRESETS = emptyList<McpServerConfig>()`.

- [ ] **Step 2: Update McpRepository.kt to use external storage directory**
  - Define `mcpDir = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/mcp").apply { mkdirs() }`.
  - Store servers in `File(mcpDir, "mcp_servers.json")`.
  - Also write `File(mcpDir, "mcp_servers.md")` containing markdown documentation of active MCP endpoints.
  - Return `emptyList()` when starting fresh with no file.

- [ ] **Step 3: Update SkillsRepository.kt to use external storage directory**
  - Define `skillsDir = File(StorageUtils.getExternalStorageDir(), "KryptonIDE/skills").apply { mkdirs() }`.
  - Save each skill as `File(skillsDir, "$name.md")`.
  - Maintain and write a synchronized `File(skillsDir, "skills.json")` containing skill metadata list.

- [ ] **Step 4: Verify compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL

---

### Task 3: Nonexec & No-Symlink Shell & Terminal Execution Engine

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/TerminalViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/KryptonShellEngine.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderEngine.kt`

**Interfaces:**
- Shared package paths:
  - `NODE_PATH=/storage/emulated/0/KryptonIDE/packages/node_modules`
  - `PYTHONPATH=/storage/emulated/0/KryptonIDE/packages/python`
- Commands targeting `.sh` scripts wrapped with `bash` to avoid `noexec`.
- Vite / Node invocations point directly to node binary and JS scripts without needing symlinks.

- [ ] **Step 1: Inject NODE_PATH and PYTHONPATH into TerminalViewModel.kt**
  - In `startShell()`, add `NODE_PATH` and `PYTHONPATH` pointing to `/storage/emulated/0/KryptonIDE/packages/node_modules` and `/storage/emulated/0/KryptonIDE/packages/python`.
  - In `sendCommand()`, if command is `./<script>` or `<script>.sh` located on external storage, invoke via `bash <script>` to escape `noexec`.

- [ ] **Step 2: Inject NODE_PATH and PYTHONPATH into RenderEngine.kt**
  - In `runShellProcess()`, inject `NODE_PATH` and `PYTHONPATH` into `pb.environment()`.
  - For FastAPI: use `python3 -m uvicorn app.main:app` or `main:app` with python module invocation.
  - For Node/Vite: resolve package binary via direct node script execution without symlinks.

- [ ] **Step 3: Update KryptonShellEngine.kt to handle bash execution and packages directory**
  - Support executing scripts through `bash` and recognizing `/storage/emulated/0/KryptonIDE/packages`.

- [ ] **Step 4: Verify compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL

---

### Task 4: In-App Dedicated Package Installer (`packages/`)

**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/ui/packages/PackageInstaller.kt`
- Create: `app/src/main/java/com/sednium/kryptonide/ui/packages/PackageInstallDialog.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/data/repository/ProjectRepository.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsDashboard.kt`

**Interfaces:**
- `PackageInstaller.getPackagesDir(): File` (`/storage/emulated/0/KryptonIDE/packages`)
- `PackageInstaller.hasRequiredPackages(template: ProjectTemplate): Boolean`
- `PackageInstaller.installPackages(template, onLog, onProgress): Boolean`
- `PackageInstallDialog(template, onDismiss, onComplete)`

- [ ] **Step 1: Create PackageInstaller.kt**
  - Manage directory structure `/storage/emulated/0/KryptonIDE/packages/node_modules` and `python`.
  - Implement verification logic for required packages (Vite, React, Express, FastAPI, Uvicorn).
  - Implement installer execution via Termux or ProcessBuilder:
    - NPM: `npm install --no-bin-links --prefix /storage/emulated/0/KryptonIDE/packages vite @vitejs/plugin-react react react-dom express`
    - PIP: `python3 -m pip install --target /storage/emulated/0/KryptonIDE/packages/python --no-warn-script-location fastapi uvicorn`
    - Fallback: Pre-packaged offline asset bundle extractor for instant initialization.

- [ ] **Step 2: Create PackageInstallDialog.kt**
  - Compose Modal/Dialog displaying:
    - Title: "Installing Shared Packages"
    - Package destination: `/storage/emulated/0/KryptonIDE/packages`
    - Step indicators (Directory Setup, NPM --no-bin-links, Python --target)
    - Animated progress bar and live terminal log stream.
    - Dismiss/Done button.

- [ ] **Step 3: Wire PackageInstaller into project creation**
  - In `ProjectsDashboard.kt` or `ProjectRepository.kt`, when a project requiring packages (`REACT_VITE`, `FASTAPI`, `VITE`, `NODE_EXPRESS`) is created:
    - Check if required packages are present.
    - If missing, trigger the in-app installer dialog.

- [ ] **Step 4: Verify compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL

---

### Task 5: Modern Studio Editor UI Upgrades

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorScreen.kt`

**Interfaces:**
- `EditorTopBar`: Breadcrumb `PROJECT / file.ext`, dropdown with extension icons and unsaved indicators, clickable unsaved badge, pill run button.
- `EditorShortcutsBar`: Floating pill keys, tactile haptic feedback (`HapticFeedbackType.TextHandleMove`), Emmet pill, command palette trigger `⌘`.
- `EditorBottomNav`: Active pill container indicator with primary tint, "AI" label with glow.

- [ ] **Step 1: Upgrade EditorTopBar**
  - Add breadcrumb styling for project name and active file name with `Urbanist` typography and chevron.
  - Enhance opened files dropdown: file extension icons, unsaved dot (`●`), active tab highlight, close button (`✕`), and "Close All Files".
  - Refine clickable `UNSAVED` pill badge with instant save.
  - Refine pill `Run`/`Preview`/`Build` button with dynamic icon and theme-safe contrast.

- [ ] **Step 2: Upgrade EditorShortcutsBar with tactile haptics**
  - Restyle accessory key buttons into rounded pill shapes (`RoundedCornerShape(8.dp)`).
  - Add `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.TextHandleMove)` on key click.
  - Ensure high-priority symbols (`Tab`, `{`, `}`, `(`, `)`, `;`, `"`, `'`, `<`, `>`, `=`, `/`, `:`, `!`, `&`, `|`, `#`, `=>`, `_`, `$`).
  - Sleek Emmet and Command Palette `⌘` buttons.

- [ ] **Step 3: Upgrade EditorBottomNav with active pill indicator**
  - Render an active pill container background behind the active item (`RoundedCornerShape(20.dp)` with `SurfaceVar` or `Primary.copy(alpha = 0.15f)`).
  - Set active tab label for "AI" with `isGlow = true`.

- [ ] **Step 4: Verify compilation**
  Run: `./gradlew compileDebugKotlin`
  Expected: BUILD SUCCESSFUL

---

### Task 6: Release Build & Device Delivery Verification

**Files:**
- Output APK: `app/build/outputs/apk/release/app-release.apk`
- Device: **INI** (`d13cd17d9cb54283bb0bc4cf62cd32b8`)

- [ ] **Step 1: Execute full release build**
  Run: `./gradlew assembleRelease`
  Expected: Exit code 0, APK generated.

- [ ] **Step 2: Verify release APK**
  Run: `ls -la app/build/outputs/apk/release/app-release.apk`
  Expected: File exists, size > 30MB.

- [ ] **Step 3: Share to phone INI via KDE Connect**
  Run: `kdeconnect-cli -d d13cd17d9cb54283bb0bc4cf62cd32b8 --share app/build/outputs/apk/release/app-release.apk`
  Expected: Exit code 0.

- [ ] **Step 4: Update walkthrough.md and task list**
