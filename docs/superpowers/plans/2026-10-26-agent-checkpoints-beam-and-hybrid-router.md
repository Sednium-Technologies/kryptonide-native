# Implementation Plan: Agent Checkpoint Time Travel, Krypton Beam, and Hybrid AI Router

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship three landmark features that turn KryptonIDE into the premier pocket workstation:
1. **Agent Checkpoint & Time Travel**: Automatic pre-turn file snapshots with 1-tap rollback for any AI modifications.
2. **Krypton Beam**: Instant embedded LAN HTTP server that mirrors live web previews to laptops, tablets, or TVs on the same local Wi-Fi.
3. **Smart Hybrid AI Router**: Automatic offline/low-signal failover from cloud models (Groq/OpenRouter) to local Pocket Server (Laya Q4).

**Architecture:**
- `CheckpointManager.kt`: Records pre-turn snapshots of files touched during agent turns (`TurnCheckpoint`). Exposes `recordPreEditState(path, content)` and `revertTurn(turnId)`.
- `AiViewModel.kt`: Hooks into tool executions (`write_file`, `edit_lines`, `insert_lines`, `patch_file`), commits checkpoints on turn start, and executes rollbacks on request. Also detects cloud network exceptions (`UnknownHostException`, `SocketTimeoutException`, `ConnectException`) and auto-reroutes to local Pocket Server.
- `LarryAIPanel.kt`: Renders a dismissible "↺ Revert Changes (N files)" card under assistant messages that modified project files.
- `KryptonBeamServer.kt`: High-performance, coroutine-driven `ServerSocket` running in background, serving project files & HTML with CORS and auto-reloading capability.
- `RenderPanel.kt` / `EditorTopBar`: Adds a **Beam** action button opening the `KryptonBeamDialog` showing the local LAN IP URL and network status.

---

### Task 1: Implement CheckpointManager for Agent Time Travel
**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/agent/checkpoint/CheckpointManager.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/ai/AiViewModel.kt`

- [x] Create `TurnCheckpoint` data class containing `turnId: String`, `timestamp: Long`, `fileSnapshots: Map<String, String?>` (relative path to original file content, null if file did not exist).
- [x] Implement `CheckpointManager` singleton or repository: `saveSnapshot(turnId, path, content)`, `getCheckpoint(turnId)`, and `revertCheckpoint(turnId, fileWriter)`.
- [x] In `AiViewModel.kt`, before executing any tool that writes or edits a file, record its original content in the active turn's checkpoint.
- [x] In `AiViewModel.kt`, add `fun revertTurn(turnId: String)` that restores original file contents, syncs editor buffer, and emits a notification toast.

---

### Task 2: UI Integration for Agent Revert in LarryAIPanel
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/ai/AiViewModel.kt` (track modified files per message)
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/LarryAIPanel.kt`

- [x] In `ChatMessage` or `AiUiState`, track `modifiedFilesByTurn: Map<String, List<String>>` (turnId -> list of modified file paths).
- [x] In `LarryAIPanel.kt`, render a sleek "Revert Turn" pill container below assistant turns that modified files:
  - Displays: "Modified N files (e.g. index.html, style.css)"
  - Button: "↺ Revert" with haptic feedback
  - Once reverted, status updates to "Reverted" with subtle checkmark.

---

### Task 3: Implement KryptonBeamServer (LAN Wi-Fi Preview Streaming)
**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/server/KryptonBeamServer.kt`
- Create: `app/src/main/java/com/sednium/kryptonide/ui/render/KryptonBeamDialog.kt`

- [x] Implement `KryptonBeamServer` using Kotlin coroutines and `ServerSocket`:
  - Dynamically binds to port 8080 (or next free port).
  - Obtains device Wi-Fi IPv4 address via `NetworkInterface.getNetworkInterfaces()`.
  - Serves current project's `previewHtml` or raw files with appropriate MIME types (`text/html`, `text/css`, `application/javascript`, `image/*`).
  - Supports CORS headers (`Access-Control-Allow-Origin: *`).
- [x] Create `KryptonBeamDialog`:
  - Shows "Krypton Beam Active" with animated Wi-Fi icon.
  - Displays URL: `http://<device_ip>:8080`.
  - "Copy URL" button and "Open Locally" button.
  - Instruction: "Open this link on any PC, Mac, iPad, or phone on the same Wi-Fi network to preview your project live."

---

### Task 4: Integrate Beam Button into Editor & Web Preview
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderPanel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorScreen.kt`

- [x] Add a `Beam` icon button (`Lucide.Cast` or `Lucide.Wifi`) in `RenderPanel.kt` top control bar.
- [x] Add "Beam to Screen" item in `EditorTopBar` More dropdown menu.
- [x] Tapping launches or toggles `KryptonBeamServer` and displays `KryptonBeamDialog`.

---

### Task 5: Implement Smart Hybrid AI Router (Offline Local Failover)
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/ai/AiViewModel.kt`

- [x] Wrap cloud inference dispatch (`engine.chat(...)`) in network exception interception.
- [x] On `UnknownHostException`, `SocketTimeoutException`, or `ConnectException`:
  - Check if `pocketManager` has a local model or if `LayaSystemOneEngine` can respond.
  - If available, automatically dispatch request to local engine:
    `"⚠️ Cloud network unreachable. Switched to Pocket Server (Offline mode)..."`
  - Prevent user prompt loss and maintain continuous conversation context.

---

### Task 6: Verification and Live Device Testing
- [x] Compile with `./gradlew assembleDebug`.
- [x] Install on Android runtime (`emulator-5554`).
- [x] Verify Agent Checkpoint & Revert UI live.
- [x] Verify KryptonBeamServer starts and responds to HTTP requests over LAN.
- [x] Capture live screenshots and hierarchy dumps for verification evidence.
