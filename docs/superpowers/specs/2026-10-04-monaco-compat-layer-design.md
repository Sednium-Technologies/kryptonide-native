# Monaco Compatibility Layer on Sora Editor Design Specification

- **Date**: 2026-10-04
- **Status**: Approved
- **Target**: KryptonIDE (Android)

---

## 1. Overview & Goal

KryptonIDE uses **Sora Editor** (`io.github.rosemoe.sora.widget.CodeEditor`), a fast, hardware-accelerated native Android text editor. However, thousands of developer plugins, snippets, themes, and syntax extensions are built for **Monaco Editor** (the editor powering VS Code web).

The goal of this subsystem is to provide a **VS Monaco Transition / Compatibility Layer** on top of Sora Editor. This allows Monaco/VS Code web plugins (JavaScript scripts, completion item providers, Monaco themes, snippet packs, and editor command hooks) to execute and integrate directly into the native Sora Editor without sacrificing native 120 FPS rendering, gesture handling, or battery efficiency.

---

## 2. Architecture: Headless V8 JS Runtime + Sora Native Bridge

```
┌────────────────────────────────────────────────────────────────────────┐
│                   Android Headless V8 WebView Runtime                   │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                      window.monaco                             │   │
│   │  - monaco.editor (defineTheme, createModel, addCommand,        │   │
│   │                   setModelMarkers, applyEdits)                 │   │
│   │  - monaco.languages (registerCompletionItemProvider,           │   │
│   │                      registerHoverProvider,                    │   │
│   │                      registerDocumentFormattingEditProvider)   │   │
│   │  - monaco.Range, Position, Selection, KeyCode, KeyMod          │   │
│   │  - TextModel (getValue, getLineContent, applyEdits)            │   │
│   └────────────────────────────────────────────────────────────────┘   │
│                                   ▲                                    │
│                                   │ Dynamic API execution              │
│                       [ Monaco Plugins / Scripts ]                     │
│               (Emmet, Snippets, Language Providers, Themes)            │
│                                   │                                    │
│                                   ▼ IPC                                │
│                       window.KryptonNative                             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ @JavascriptInterface
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                     MonacoRuntime.kt & MonacoBridge.kt                 │
│                                                                        │
│   - Orchestrates headless WebView execution & plugin script loading    │
│   - Dispatches completion queries from Sora to JS providers            │
│   - Syncs text buffer changes into JS TextModel                        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │ Direct Native Binding
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                          Sora Editor Host                              │
│                                                                        │
│   - Sora Editor View (CodeEditor)                                      │
│   - KryptonLspLanguage (Aggregates LSP + Monaco completions)           │
│   - EditorColorScheme (Auto-generated from monaco.editor.defineTheme)  │
│   - CommandPalette / KeymapManager (Triggers Monaco action hooks)      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Core Components

### 3.1 `monaco-shim.js`
A bundled, standalone JavaScript polyfill that defines `window.monaco`:
- **`monaco.editor`**:
  - `defineTheme(themeName, themeData)`: Transmits JSON color scheme to native via `window.KryptonNative.onThemeDefined(name, json)`.
  - `setTheme(themeName)`: Activates theme via `window.KryptonNative.onThemeChanged(name)`.
  - `createModel(value, language, uri)`: Instantiates a virtual `TextModel`.
  - `addCommand(keybinding, handler)` / `addAction(action)`: Registers actions with native bridge.
  - `setModelMarkers(model, owner, markers)`: Passes diagnostic errors/warnings to Sora.
- **`monaco.languages`**:
  - `registerCompletionItemProvider(languageId, provider)`: Registers provider callback.
  - `registerHoverProvider(languageId, provider)`: Registers hover documentation provider.
  - `registerDocumentFormattingEditProvider(languageId, provider)`: Registers formatter.
- **Classes**:
  - `monaco.Range`, `monaco.Position`, `monaco.Selection`.
  - Enums: `CompletionItemKind`, `MarkerSeverity`, `KeyCode`, `KeyMod`.

### 3.2 `MonacoRuntime.kt`
- Manages an invisible Android `WebView` configured with JavaScript enabled and DOM storage.
- Loads plugins from:
  1. Internal bundled assets (`assets/monaco/plugins/`).
  2. External shared storage (`/storage/emulated/0/KryptonIDE/plugins/monaco/`).
- Dispatches async completion requests with a 350ms timeout to prevent UI freezes.
- Synchronizes current open file contents into the active JS `TextModel`.

### 3.3 `MonacoBridge.kt`
- Annotated with `@JavascriptInterface` to receive events from JS into Kotlin:
  - `onThemeDefined(name: String, themeJson: String)`
  - `onThemeChanged(name: String)`
  - `onCompletionResult(requestId: String, itemsJson: String)`
  - `onApplyEdits(editsJson: String)`
  - `onConsoleLog(level: String, message: String)`

### 3.4 `SoraMonacoAdapter.kt`
- Converts Monaco themes (`colors` map + token `rules`) directly to Sora's `EditorColorScheme` and `TextMateColorScheme`.
- Converts `MonacoCompletionItem` into Sora's `CompletionItem` with snippet expansion.
- Dispatches line/column text edits to Sora's `Content` buffer.

### 3.5 Language & Completion Hook (`KryptonLspLanguage.kt`)
- Extends Sora's `performCompletion` to query both active Language Server Protocol (LSP) servers and active Monaco completion providers simultaneously, merging and de-duplicating results.

---

## 4. Performance & Safety Strategy

1. **Non-blocking UI**: All JS evaluations are executed asynchronously via `evaluateJavascript` on background coroutines.
2. **Timeout Protection**: Auto-complete requests to Monaco plugins are capped at 350ms. If a plugin takes longer, native and LSP completions render immediately without delay.
3. **Error Isolation**: Script errors inside plugins are caught, logged, and isolated without crashing the Android app.
4. **Offline & Battery Friendly**: Operates entirely offline with zero network dependence and no layout/rendering passes in the headless runtime.

---

## 5. Verification Plan

1. **Unit & Build Verification**: Compile Kotlin with `./gradlew compileDebugKotlin` to ensure type safety.
2. **Theme Verification**: Verify `monaco.editor.defineTheme` converts and renders custom Monaco themes inside Sora.
3. **Completion Verification**: Register a sample Monaco completion provider (e.g. HTML/CSS or custom snippets) and verify suggestions appear in the Sora auto-complete popup.
4. **Release APK Assemble & Share**: Run `./gradlew assembleRelease` and send to device for on-device testing.
