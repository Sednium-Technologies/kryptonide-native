# Comprehensive Page & Template Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all discovered UX friction points, UI overflows on 720p/budget screens, performance bottlenecks in search/disk scanning, runtime noexec permissions for systems templates, console formatting in webview, and template preview routing across KryptonIDE.

**Architecture:** 
1. Exclude high-churn dependency directories (`node_modules`, `.git`, `build`, `target`, `.next`, `dist`, `.gradle`) in `FileWatcherService.kt` and `EditorViewModel.kt` to eliminate UI freezing and 10,000+ unnecessary DB rows.
2. Clamp and weight breadcrumbs in `EditorScreen.kt` to prevent top bar overflow on 720p screens.
3. Eliminate `noexec` permission errors for C, C++, Rust, and Go by directing build artifacts and temporary targets (`CARGO_TARGET_DIR`, `GOTMPDIR`, clang `-o`) to internal executable sandbox storage (`context.cacheDir/tmp`).
4. Upgrade WebView console bridge in `RenderEngine.kt` to serialize objects into formatted JSON strings (`JSON.stringify(a, null, 2)`).
5. Enable Markdown preview routing for `.md` and `.markdown` files across all templates in `RenderTemplates.kt`.
6. Add "RAM Safe" compatibility filter in `PocketServerScreen.kt` for low-end device protection.
7. Improve Search in `ProjectsDashboard.kt` to match both project names and template types, and validate ZIP imports against 0-file extractions.

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines/Flow, Android WebKit, Linux ProcessBuilder / JNI.

**Spec:** Multi-persona focus group feedback and simulated launch findings.

---

### Task 1: Exclude Noise Directories from FileWatcherService and Global Search
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/service/FileWatcherService.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorViewModel.kt`

- [ ] Add `IGNORED_SCAN_DIRS` set (`node_modules`, `.git`, `.gradle`, `build`, `.next`, `dist`, `target`, `.turbo`, `.idea`, `__pycache__`) in `FileWatcherService.kt`.
- [ ] Filter out ignored directories in `scanDir()` so they are not indexed into database.
- [ ] In `EditorViewModel.search()`, skip files within these directories and trim search query.

---

### Task 2: Fix TopBar Breadcrumb Overflow on 720p Screens
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/EditorScreen.kt`

- [ ] Wrap `projectName` in `Modifier.weight(0.45f, fill = false)` with `TextOverflow.Ellipsis`.
- [ ] Set `activeFileName` to `Modifier.weight(0.55f, fill = false)` with `TextOverflow.Ellipsis`.
- [ ] Ensure action buttons (`Preview`/`Run`, `Save`, `More actions`) remain pinned and accessible without horizontal clipping.

---

### Task 3: Fix `noexec` Execution for Systems Templates (Rust, Go, C, C++)
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/TerminalViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderEngine.kt`

- [ ] In `TerminalViewModel.kt`, inject `CARGO_TARGET_DIR`, `GOTMPDIR`, and `TMPDIR` pointing to internal `cacheDir/tmp`.
- [ ] In `RenderEngine.kt`, inject `CARGO_TARGET_DIR`, `GOTMPDIR`, and `TMPDIR` into `ProcessBuilder` environment.
- [ ] In `RenderEngine.resolveRuntime()` for `CPP` and `C`, output compiled binary to internal storage (`context.cacheDir/krypton_bin`) instead of `projectDir/.krypton_out`.

---

### Task 4: Fix Console Object Formatting and Markdown Preview Routing
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderEngine.kt`

- [ ] In `consoleBridgeScript()`, update `formatArg` and `relay` to pass the serialized JSON message to `console.log` so `WebChromeClient` receives human-readable JSON instead of `[object Object]`.
- [ ] In `RenderTemplates.isWebPreview()`, include `.md` and `.markdown` files so Markdown preview is available across all templates including `BLANK`.

---

### Task 5: Add RAM Safe Filter to Pocket Server & Improve Project Dashboard
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/ui/PocketServerScreen.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsDashboard.kt`

- [ ] In `PocketServerScreen.kt`, add a "RAM Safe" toggle filter chip to hide models exceeding available memory.
- [ ] In `ProjectsDashboard.kt`, expand search filter to match template name in addition to project name.
- [ ] In `ProjectsDashboard.kt`, check for `fileCount == 0` in ZIP importer and notify user if extraction was empty or password-protected.

---

### Task 6: Verification and Live Device Testing
- [ ] Run `./gradlew assembleDebug` and verify exit code 0.
- [ ] Deploy updated APK to emulator (`adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`).
- [ ] Verify UI layouts, search responsiveness, and RAM filter via live screenshots and hierarchy dumps.
