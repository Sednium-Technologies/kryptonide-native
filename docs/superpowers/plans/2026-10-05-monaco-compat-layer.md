# Monaco Compatibility Layer on Sora Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a robust VS Monaco transition layer on top of Sora Editor in KryptonIDE, enabling Monaco/VS Code plugins, themes, completions, and commands to run smoothly.

**Architecture:** A lightweight headless Android V8 WebView runtime executes an embedded `monaco-shim.js` polyfill, mapping Monaco APIs (`monaco.editor`, `monaco.languages`, `TextModel`, `defineTheme`, `registerCompletionItemProvider`) bidirectionally to Sora Editor's native canvas and completion engine.

**Tech Stack:** Kotlin, Jetpack Compose, Sora Editor (`io.github.rosemoe.sora`), Android WebView / V8 JavaScript Engine, Kotlin Coroutines.

**Spec:** `docs/superpowers/specs/2026-10-04-monaco-compat-layer-design.md`

## Global Constraints
- Native 120 FPS performance of Sora Editor must remain untouched (no DOM-based editor replacement).
- Zero additional native C++ `.so` binaries added to APK (leverage system V8 WebView).
- Asynchronous completion queries must enforce a strict 350ms timeout so typing is never blocked.
- Backward compatibility with existing LSP servers and Sora themes must be 100% preserved.

---

### Task 1: Monaco JavaScript Shim Polyfill (`monaco-shim.js`)

**Files:**
- Create: `app/src/main/assets/monaco/monaco-shim.js`

**Interfaces:**
- Produces: `window.monaco` global object with:
  - `monaco.editor` (`defineTheme`, `setTheme`, `createModel`, `getModel`, `addCommand`, `addAction`, `setModelMarkers`, `applyEdits`)
  - `monaco.languages` (`registerCompletionItemProvider`, `registerHoverProvider`, `registerDocumentFormattingEditProvider`, `CompletionItemKind`)
  - `monaco.Range`, `monaco.Position`, `monaco.Selection`, `monaco.KeyCode`, `monaco.KeyMod`, `monaco.MarkerSeverity`
  - `TextModel` mock with `getValue()`, `setValue()`, `getLineContent()`, `getLineCount()`, `applyEdits()`
  - IPC callbacks via `window.KryptonNative`

- [ ] **Step 1: Write `app/src/main/assets/monaco/monaco-shim.js`**
  - Implement full Monaco polyfill namespace.
  - Implement async completion item provider invocation with request ID tracking.
  - Add mock DOM and window environment stubs.

---

### Task 2: Monaco Bridge & Headless Runtime Engine (`MonacoRuntime.kt`, `MonacoBridge.kt`, `MonacoModels.kt`)

**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/ui/monaco/MonacoModels.kt`
- Create: `app/src/main/java/com/sednium/kryptonide/ui/monaco/MonacoBridge.kt`
- Create: `app/src/main/java/com/sednium/kryptonide/ui/monaco/MonacoRuntime.kt`

**Interfaces:**
- Consumes: `app/src/main/assets/monaco/monaco-shim.js`
- Produces:
  - `MonacoRuntime.init(context: Context)`
  - `MonacoRuntime.loadPluginScript(id: String, script: String)`
  - `MonacoRuntime.syncModel(uri: String, languageId: String, content: String)`
  - `MonacoRuntime.requestCompletions(languageId: String, line: Int, column: Int, word: String): List<MonacoCompletionItem>`
  - `MonacoRuntime.onThemeDefined: (name: String, json: String) -> Unit`
  - `MonacoRuntime.onThemeChanged: (name: String) -> Unit`

- [ ] **Step 1: Create `MonacoModels.kt`** defining `MonacoCompletionItem`, `MonacoRange`, `MonacoThemeDefinition`, `MonacoTextEdit`.
- [ ] **Step 2: Create `MonacoBridge.kt`** with `@JavascriptInterface` receiving IPC events into Kotlin callback channels.
- [ ] **Step 3: Create `MonacoRuntime.kt`** with headless WebView management, coroutine dispatch, and script loader.

---

### Task 3: Sora Native Monaco Adapter (`SoraMonacoAdapter.kt`)

**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/ui/monaco/SoraMonacoAdapter.kt`

**Interfaces:**
- Consumes: `MonacoModels.kt`, Sora `CodeEditor`, `EditorColorScheme`, `Content`
- Produces:
  - `SoraMonacoAdapter.monacoThemeToEditorColorScheme(themeJson: String): EditorColorScheme`
  - `SoraMonacoAdapter.monacoCompletionToSoraItem(item: MonacoCompletionItem): io.github.rosemoe.sora.lang.completion.CompletionItem`
  - `SoraMonacoAdapter.applyMonacoEdits(editor: CodeEditor, edits: List<MonacoTextEdit>)`

- [ ] **Step 1: Implement theme mapping** from Monaco `colors` and `rules` to Sora `EditorColorScheme`.
- [ ] **Step 2: Implement completion mapping** converting Monaco kinds, insertText, and snippet placeholders into Sora `CompletionItem`.
- [ ] **Step 3: Implement buffer edit translator** mapping 1-based Monaco range edits to Sora's `Content` lines/columns.

---

### Task 4: Hook Monaco Completions & Themes into Sora Editor (`SoraEditorHost.kt`, `KryptonLspLanguage.kt`)

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/SoraEditorHost.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/KryptonLspLanguage.kt`

**Interfaces:**
- Consumes: `MonacoRuntime`, `SoraMonacoAdapter`
- Produces: Unified editor auto-complete displaying both LSP and Monaco plugin suggestions; dynamic theme activation.

- [ ] **Step 1: Wire `MonacoRuntime` into `SoraEditorHost`** for buffer change notifications and theme changes.
- [ ] **Step 2: Update `KryptonLspLanguage`** to query `MonacoRuntime.requestCompletions` and merge into Sora completion list.
- [ ] **Step 3: Add Monaco theme listener** in `SoraEditorHost` applying `onThemeDefined` and `onThemeChanged`.

---

### Task 5: Plugin Store Integration, Verification & Release

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/plugins/PluginManager.kt`

- [ ] **Step 1: Connect `PluginManager`** to initialize `MonacoRuntime` and scan for Monaco plugins in `/storage/emulated/0/KryptonIDE/plugins/monaco/`.
- [ ] **Step 2: Verify compilation** with `./gradlew compileDebugKotlin`.
- [ ] **Step 3: Assemble release APK** with `./gradlew assembleRelease`.
- [ ] **Step 4: Transmit release APK** to user's device (`INI`) via `kdeconnect-cli`.
