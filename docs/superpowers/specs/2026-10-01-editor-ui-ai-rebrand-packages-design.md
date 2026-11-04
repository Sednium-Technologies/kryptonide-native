# Specification: Editor UI Upgrades, AI Rebranding, External Skills/MCP Storage, and In-App Packages Architecture

**Date**: 2026-10-01  
**Project**: KryptonIDE  
**Status**: Approved Design  

---

## 1. Executive Summary

This document specifies the architectural changes and enhancements for KryptonIDE:
1. **Editor UI Redesign (Modern Studio Aesthetic)**: Elevating the top navigation bar with breadcrumbs and open files management, an active pill bottom navigation, and a floating tactile shortcuts accessory bar with haptic feedback.
2. **Larry to AI Rebranding**: Transitioning all naming from "Larry" / "Larry AI" to "AI" / "AI Assistant" while preserving the full Bring Your Own Key (BYOK) multi-provider architecture.
3. **External Storage for Skills & MCP (Dual JSON & Markdown)**: Storing skills and MCP server definitions in `/storage/emulated/0/KryptonIDE/skills` and `/storage/emulated/0/KryptonIDE/mcp`, serialized in both `.json` and `.md` formats, with no default mock MCP servers.
4. **Shell Execution Without Exec/Symlink Violations**: Overcoming Android external storage `noexec` and missing symlink capabilities by invoking interpreters explicitly (`bash`, `node`, `python`) and setting shared environment paths.
5. **Dedicated In-App Package Installer (`packages/`)**: Establishing a shared packages repository in `/storage/emulated/0/KryptonIDE/packages` with `--no-bin-links` for npm and `--target` for pip, automatically populated or verified during project creation with a dedicated in-app UI.

---

## 2. Editor UI Redesign (Modern Studio Aesthetic)

### 2.1 Top Bar (`EditorTopBar.kt` & `EditorScreen.kt`)
- **Breadcrumb Hierarchy**: Displays `PROJECT_NAME / file_name.ext` in `Urbanist` typography with a chevron toggle.
- **Open Files Dropdown**:
  - Displays count: `OPENED FILES (N)`.
  - Lists each opened buffer with file extension indicators, unsaved status dot (`●`), active buffer highlight, and a close button (`✕`).
  - Includes a "Close All Files" action button.
- **Micro-Action Save Pill**:
  - Unsaved files display an amber `UNSAVED` pill badge.
  - Tapping the badge or Save icon button instantly saves buffer to disk, updates status to `SAVED` (emerald), and triggers background linters.
- **Pill Run/Preview Button**:
  - Full-pill button with icon and label reflecting the active project template:
    - Android: `Build` (`Icons.Default.Build`)
    - Web / React / Vite / Next.js: `Preview` (`Icons.Default.PlayArrow`)
    - Python / FastAPI / Shell: `Run` (`Icons.Default.PlayArrow`)
    - Disabled / General: `Shell` (`Icons.Default.Terminal`)

### 2.2 Floating Keyboard Accessory Row (`EditorShortcutsBar.kt`)
- Sits pinned directly above the soft keyboard with `imePadding()`.
- Built with rounded pill buttons (`RoundedCornerShape(8.dp)`) with subtle surface styling.
- Integrates haptic feedback on key click:
  ```kotlin
  val haptic = LocalHapticFeedback.current
  haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
  ```
- Symbol keys: `Tab`, `{`, `}`, `(`, `)`, `;`, `"`, `'`, `<`, `>`, `=`, `/`, `:`, `!`, `&`, `|`, `#`, `=>`, `_`, `$`.
- Accessory actions: `Emmet` expansion pill and `⌘` Command Palette trigger.

### 2.3 Bottom Navigation (`EditorBottomNav.kt`)
- Replaces plain icon tints with an active pill indicator (pill background container `RoundedCornerShape(20.dp)` with subtle primary highlight).
- Tabs:
  - `Edit` (`Icons.Default.Code`)
  - `GitHub` (`Icons.Default.AccountTree`)
  - `AI` (`Icons.Default.AutoAwesome`) — rebranded from "Larry"
  - `Plugins` (`Icons.Default.Extension`)
  - `Settings` (`Icons.Default.Settings`)

---

## 3. Larry to AI Rebranding & BYOK Continuity

### 3.1 Rebranding Scope
- Bottom navigation tab: `"Larry"` -> `"AI"`.
- AI Chat Panel (`LarryAIPanel.kt` / `AiPanel.kt`):
  - Top title: "AI Assistant".
  - Empty state text: "How can I help you code today?".
  - Input field placeholder: "Ask AI or describe code changes...".
- Command Palette (`EditorCommandPalette.kt`):
  - Category: `PaletteCategory.AI("AI")`.
  - Commands: "Open AI", "Ask AI to explain \"$word\"", "Ask AI to fix issue", "Generate code with AI", "Review file with AI".
- File Explorer (`FileExplorerPanel.kt`): Context menu option changed to "Send to AI".
- System Prompt (`LarrySystemPrompt.kt`):
  - Updated identity: *"You are **AI**, the agentic coding assistant embedded in **KryptonIDE**... Name: AI"*.
- System / AI Settings (`SettingsScreen.kt`): Labeled as "AI Configuration" and "AI Models & Keys".

### 3.2 BYOK (Bring Your Own Key) Verification
- Preserves full support for user-supplied API keys for:
  - Google Gemini (`gemini-3.5-flash`, `gemini-3.5-pro`)
  - Anthropic Claude (`claude-sonnet-4-6`, `claude-opus-4-6`)
  - OpenAI (`gpt-5.5`, `gpt-5-mini`, `o4-mini`, `gpt-4o`)
  - DeepSeek (`deepseek-v4-pro`, `deepseek-chat`)
  - Moonshot / Kimi (`kimi-k3`)
  - Groq (`llama-4-scout`, `llama-3.3-70b-versatile`)
  - Local Ollama endpoints (`localhost:11434`)

---

## 4. Skills & MCP External Storage Architecture

### 4.1 Storage Locations
All configurations are stored in the user-accessible external storage root `/storage/emulated/0/KryptonIDE/`:
- **Skills**: `/storage/emulated/0/KryptonIDE/skills/`
- **MCP**: `/storage/emulated/0/KryptonIDE/mcp/`
Fallback to `context.filesDir` is maintained if Android storage permission is not granted.

### 4.2 Skills Storage (Dual Format)
- Individual Markdown files: `/storage/emulated/0/KryptonIDE/skills/<skill_name>.md`.
- Synchronized JSON registry: `/storage/emulated/0/KryptonIDE/skills/skills.json`:
  ```json
  [
    {
      "name": "react_guidelines",
      "fileName": "react_guidelines.md",
      "description": "Best practices for React components",
      "sizeBytes": 1420,
      "updatedAt": 1759320000000
    }
  ]
  ```

### 4.3 MCP Storage & Preset Removal
- MCP Server Configs: `/storage/emulated/0/KryptonIDE/mcp/mcp_servers.json`.
- MCP Markdown Context: `/storage/emulated/0/KryptonIDE/mcp/mcp_servers.md`.
- **Default Presets**: `DEFAULT_MCP_PRESETS` is set to an empty list (`emptyList()`). No mock GitHub, Web Fetch, or Filesystem servers are inserted by default. The user configures or imports real endpoints.

---

## 5. Nonexec & No-Symlink Shell Architecture

### 5.1 Problem Statement
On Android, `/storage/emulated/0` is mounted `noexec` (cannot execute binaries directly) and uses an emulated FAT/FUSE filesystem layer that does not support symlinks (`ln -s` returns `EPERM`). Normal Node (`npm install`) and Python setups that create executable symlinks in `node_modules/.bin` or virtualenv symlinks fail.

### 5.2 Resolution Strategy
1. **Explicit Interpreter Invocations**:
   - Instead of executing `./script.sh` or `./node_modules/.bin/vite`, commands run via the interpreter:
     - Shell scripts: `bash /path/to/script.sh`
     - Node scripts: `node /storage/emulated/0/KryptonIDE/packages/node_modules/vite/bin/vite.js`
     - Python scripts/modules: `python3 -m uvicorn app.main:app`
2. **Environment Variable Injection**:
   - `NODE_PATH`: `/storage/emulated/0/KryptonIDE/packages/node_modules`
   - `PYTHONPATH`: `/storage/emulated/0/KryptonIDE/packages/python`
   - Injected into `TerminalViewModel`, `RenderEngine`, and toolchain process builders.

---

## 6. Shared In-App Packages Architecture (`packages/`)

### 6.1 Directory Structure
```
/storage/emulated/0/KryptonIDE/packages/
  ├── node_modules/         # Shared Node packages without symlinks
  ├── python/               # Shared Python packages installed via --target
  └── packages_manifest.json
```

### 6.2 In-App Package Installer (`PackageInstaller.kt` & `PackageInstallDialog.kt`)
- When creating a project with templates requiring dependencies (e.g. `FASTAPI`, `REACT_VITE`, `VITE`, `NODE_EXPRESS`):
  - Checks if `/storage/emulated/0/KryptonIDE/packages` contains the required packages.
  - If missing, displays an in-app installation modal showing:
    - Step 1: Preparing packages directory
    - Step 2: Installing NPM modules without symlinks (`--no-bin-links`)
    - Step 3: Installing Python modules with `--target`
    - Live terminal output log viewer
- Execution logic:
  - NPM: `npm install --no-bin-links --prefix /storage/emulated/0/KryptonIDE/packages vite @vitejs/plugin-react react react-dom express`
  - PIP: `python3 -m pip install --target /storage/emulated/0/KryptonIDE/packages/python --no-warn-script-location fastapi uvicorn`
- Allows projects to run immediately without per-project package duplication or symlink failures.

---

## 7. Verification Plan
1. **Compile & Lint Verification**: Run `./gradlew compileDebugKotlin` to verify no type or import errors.
2. **Storage Verification**: Confirm skills and MCP files write to `/storage/emulated/0/KryptonIDE/skills` and `/storage/emulated/0/KryptonIDE/mcp` as JSON and MD.
3. **Noexec & Packages Verification**: Verify commands execute via bash with `NODE_PATH` and `PYTHONPATH` set to `/storage/emulated/0/KryptonIDE/packages`.
4. **Release Build**: Run `./gradlew assembleRelease` and deliver APK to device **INI** via KDE Connect.
