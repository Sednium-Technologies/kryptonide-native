# Implementation Plan: Needle 3 & In-App Model Download Hub

**Goal:** Implement the on-device AI enhancements:
1. **Needle 3 (Cactus Compute)** integration: Ultra-compact 80MB tool-calling and embedding specialist in `PocketModels.kt` requiring only 256MB RAM.
2. **In-App Download Manager**: Direct 1-click model downloading to `/storage/emulated/0/KryptonIDE/models/` with progress tracking, pause/resume, and background notifications.
3. **Downloadable Catalog UI**: Upgrade `PocketServerScreen.kt` with live "Download", "Downloading (XX%)", and "Installed" states with RAM safety gating.
4. **Needle 3 Local Tool Dispatcher**: Schema-constrained parsing bridge in `NeedleToolDispatcher.kt` wiring into `AgentToolRegistry.kt`.
5. **Live Verification**: Build, deploy to `emulator-5554`, verify live in UI, and record evidence.

---

### Task 1: Add Needle 3 and Verified Model URLs to PocketModels.kt
- [x] Add `needle-3-q4` (Cactus Compute, 80MB, 256MB min RAM, Q4_K_M, tool-calling + embeddings).
- [x] Ensure all predefined models have verified download URLs and accurate byte sizes.

### Task 2: Implement ModelDownloadManager.kt
- [x] Create `com.sednium.kryptonide.agent.pocket.ModelDownloadManager`:
  - Coroutine & URL streaming with buffered I/O into `.tmp` file, renaming to `.gguf` upon completion.
  - StateFlow tracking `Map<String, DownloadState>` (status, progress float, downloadedBytes, totalBytes).
  - Background notification with progress updates.
  - Automatically triggers `StorageModelScanner.scan()` upon download completion.

### Task 3: In-App Download UI in PocketServerScreen.kt
- [x] In `Recommended Model Catalog`, check if model file already exists in scanned storage.
- [x] If not downloaded: render "Download (~80MB)" button with `Lucide.Download`.
- [x] If downloading: render live percentage progress bar and cancel button.
- [x] If downloaded: render "Installed" badge with `Lucide.CircleCheck` and "Launch" action.
- [x] Enforce RAM warning dialog if user attempts to download a model exceeding available device RAM.

### Task 4: Needle 3 Tool Calling Integration
- [x] Create `NeedleToolDispatcher.kt` parsing prompts against active `AgentToolRegistry` schemas using grammar-constrained output tokens.
- [x] Wire into `LayaSystemOneEngine.kt` as fast-path tool execution.

### Task 5: Compilation, Deployment & Live Verification
- [x] Compile with `./gradlew assembleDebug`.
- [x] Deploy to `emulator-5554`.
- [x] Verify Needle 3 appears in Pocket Server with 80MB size and 256MB RAM requirement.
- [x] Test download interaction live.
- [x] Capture live screenshots and write verification report.
