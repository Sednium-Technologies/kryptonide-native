# Krypton IDE — Architecture Upgrades, Enhancements & Porting Guide

**Target Audience:** CEO & Core Engineering Team  
**Purpose:** Technical specifications, architecture designs, feature implementations, and bug fixes added to the native Krypton IDE Android version, structured for direct porting into the **React Legacy / Web Version**.  
**Date:** September 13, 2026  
**Status:** Implemented, Verified, and Deployed in Release APK  

---

## Table of Contents
1. [Executive Summary](#1-executive-summary)
2. [AI Assistant (Larry AI) — 2026 Frontier Models Roster](#2-ai-assistant-larry-ai--2026-frontier-models-roster)
3. [Model Context Protocol (MCP) Connectors Integration](#3-model-context-protocol-mcp-connectors-integration)
4. [Agent Skills Architecture (.md Import & Frontmatter)](#4-agent-skills-architecture-md-import--frontmatter)
5. [Dynamic Multi-Stage Plugin Ecosystem](#5-dynamic-multi-stage-plugin-ecosystem)
6. [File Saving, Auto-Save & Disk Synchronization](#6-file-saving-auto-save--disk-synchronization)
7. [Workspace File Explorer — 60fps & Quick Actions](#7-workspace-file-explorer--60fps--quick-actions)
8. [Opened Files Dropdown Navigation](#8-opened-files-dropdown-navigation)
9. [Projects Dashboard & Terminal Ergonomics](#9-projects-dashboard--terminal-ergonomics)
10. [React Porting Architecture & Component Mapping](#10-react-porting-architecture--component-mapping)

---

## 1. Executive Summary

Over the latest development cycle, Krypton IDE underwent significant architecture modernization. The changes address critical performance bottlenecks on lower-spec hardware, overhaul the AI and plugin infrastructures with modern 2026 standards, improve file editing data integrity, and refine UX ergonomics across the workspace.

### Key Pillars of the Upgrade:
- **Zero Data Loss Guarantee**: Auto-save on blur/switch/exit, disk synchronization with project root paths, interactive unsaved indicators.
- **60fps Fluid Workspace**: Eliminated artificial staggered delays in Lazy lists, optimized project indexing with caching, and streamlined file browsing.
- **2026 Frontier AI**: Replaced obsolete 2024/2025 LLMs with frontier 2026 models (`gemini-3.5-flash`, `deepseek-v4-pro`, `gpt-5.5`, `claude-sonnet-4-6`, `llama-4-scout`, and local Ollama models).
- **Extensibility Standards**: Implemented Model Context Protocol (MCP) server support, YAML frontmatter `.md` skill imports, and true multi-stage plugin installations with live streaming logs.

---

## 2. AI Assistant (Larry AI) — 2026 Frontier Models Roster

### Problem & Motivation
The previous configuration referenced older 2024/2025 model checkpoints (such as early Gemini 2.x and GPT-4o variants). The prompt and provider catalogue was completely refreshed using the 2026 `awesome-free-models` registry.

### Architecture & Provider Specs
The models are categorized by provider with default fallback models, custom base URL support, and API key management:

| Provider | Provider ID | Default Model | Supported Model Checkpoints |
| :--- | :--- | :--- | :--- |
| **Google Gemini** | `google` | `gemini-3.5-flash` | `gemini-3.5-flash`, `gemini-3.5-pro`, `gemini-3-flash`, `gemini-3-pro`, `gemini-2.5-flash`, `gemini-2.5-pro` |
| **DeepSeek** | `deepseek` | `deepseek-v4-pro` | `deepseek-v4-pro`, `deepseek-v4-lite`, `deepseek-chat` |
| **OpenAI** | `openai` | `gpt-5.5` | `gpt-5.5`, `gpt-5-mini`, `o4-mini`, `o3-mini`, `gpt-4o` |
| **Anthropic** | `anthropic` | `claude-sonnet-4-6` | `claude-sonnet-4-6`, `claude-opus-4-6`, `claude-3-7-sonnet`, `claude-3-5-haiku` |
| **Moonshot (Kimi)**| `moonshot` | `kimi-k3` | `kimi-k3`, `kimi-k2.5`, `moonshot-v1-32k` |
| **Groq Cloud** | `groq` | `llama-4-scout` | `llama-4-scout`, `llama-3.3-70b-versatile`, `deepseek-r1-distill-llama-70b` |
| **Ollama (Local)** | `ollama` | `llama3.2` | `llama3.2`, `codellama`, `deepseek-coder-v2`, `qwen2.5-coder` (Base URL: `http://localhost:11434`) |

### Key Code Files Modified
- `app/src/main/java/com/sednium/kryptonide/ui/larry/LarryProviders.kt`: Contains provider definitions, model rosters, and endpoint templates.
- `app/src/main/java/com/sednium/kryptonide/data/storage/SettingsStore.kt`: Default provider initialized to `google` with `gemini-3.5-flash`.

---

## 3. Model Context Protocol (MCP) Connectors Integration

### Overview
To allow Larry AI to seamlessly interact with external tool servers and local environments, a dedicated Model Context Protocol (MCP) connector module was implemented.

### Data Model Schema
```typescript
interface McpServer {
  id: string;              // UUID
  name: string;            // e.g., "GitHub MCP Server"
  type: "sse" | "http";    // Transport protocol
  url: string;             // Endpoint URI
  authToken?: string;      // Bearer token / API key
  isEnabled: boolean;      // Toggle switch
  toolsCount: number;      // Number of reported tools
}
```

### Pre-Configured Presets
1. **GitHub MCP Server**: Connects to GitHub API for issues, PRs, and repository metadata queries.
2. **Web Fetch MCP Server**: Provides scraping and live API fetch capabilities for external documentation.
3. **Filesystem MCP Server**: Exposes structured directory exploration tools to the agent.
4. **Custom SSE / HTTP Endpoint**: Allows user-defined internal enterprise MCP servers.

### Context Injection Logic
When a conversation starts, `AiViewModel` queries `McpRepository.getMcpPromptContext()`. All enabled MCP servers are serialized into the LLM system prompt:
```
Connected Model Context Protocol (MCP) Tools:
- Server: GitHub MCP (https://api.github.com/mcp) [Enabled]
- Server: Web Fetch MCP (https://mcp.fetch.local) [Enabled]
When user instructions require external context, request MCP tool execution.
```

---

## 4. Agent Skills Architecture (.md Import & Frontmatter)

### Design Decision
**Important:** Per UX guidelines, Skills were kept strictly in **Settings > Agent Skills** and were **not** merged into the Plugins tab to prevent confusing runtime plugins with AI prompt instructions.

### `.md` File Import & Frontmatter Parsing
Users can now import custom skills via markdown files (`.md`) from their device or cloud storage. The parser extracts YAML frontmatter to automatically name and describe the skill:

```markdown
---
name: Kotlin Coroutines Expert
description: Audits code for structured concurrency and scope leaks
---
You are an expert in Kotlin coroutines and Flow. Always check for proper CancellationExceptions...
```

### Parser Implementation Pattern
```kotlin
fun importSkill(fileName: String, content: String): AgentSkill {
    val frontmatterRegex = Regex("""^---\s*\n([\s\S]*?)\n---""", RegexOption.MULTILINE)
    val match = frontmatterRegex.find(content)
    var name = fileName.removeSuffix(".md")
    var description = "Custom imported skill"
    
    if (match != null) {
        val yaml = match.groupValues[1]
        yaml.lines().forEach { line ->
            when {
                line.startsWith("name:") -> name = line.substringAfter("name:").trim()
                line.startsWith("description:") -> description = line.substringAfter("description:").trim()
            }
        }
    }
    val instructions = content.replace(frontmatterRegex, "").trim()
    return AgentSkill(id = UUID.randomUUID().toString(), name = name, description = description, instructions = instructions)
}
```

---

## 5. Dynamic Multi-Stage Plugin Ecosystem

### Problems Resolved
- Plugins previously simulated a fake 600ms static timer.
- Users had no visibility into what was happening (downloading vs sandbox config).
- The delete button in the plugins list had an oversized background hitbox.
- Users could not install their own community `.vsix` or `.zip` files.

### 5-Stage Installation Lifecycle
Plugins now transition through an observable state machine with accurate percentage progression:
1. **15% — Resolving dependencies & manifest**: Validates `package.json` / plugin metadata and compatibility.
2. **40% — Downloading package & assets**: Retrieves binary artifacts from repository or local storage.
3. **65% — Extracting and verifying bundle**: Unpacks `.vsix` / `.zip` into the app storage sandbox.
4. **85% — Configuring execution sandbox / Termux hook**: Sets up executable permissions, runtime environment, and CLI hooks.
5. **100% — Activated**: Marks plugin as enabled and ready for editor execution.

### UI Additions
- **Real-Time Progress Indicator**: Linear progress bar with stage description (`installStage`) and numeric percentage.
- **Live Installation Log Viewer**: "Logs" button opens a scrollable monospace dialog displaying streaming log lines emitted during installation.
- **Install from File Action**: Document picker in top bar supporting `.vsix` and `.zip` plugin archives.
- **Fixed Delete Button**: Styled with compact 32dp bounds and proper padding matching the item container.

---

## 6. File Saving, Auto-Save & Disk Synchronization

### Problems Resolved
- Switching editor tabs or navigating back to dashboard without saving caused data desynchronization or lost edits.
- The `UNSAVED` status badge in the header was non-interactive.
- Disk sync wrote to isolated sandbox paths rather than the active project root directory.

### Enhancements Made
1. **Auto-Save Lifecycle Hook**:
   - In `EditorScreen.kt`, switching the active file tab or triggering `onBack` now automatically commits the buffer:
     ```kotlin
     activeFileId?.let { id ->
         if (fileMap[id]?.isUnsaved == true) {
             editorVm.updateContent(id, soraHost.getContent())
             editorVm.saveFileToDisk(id)
         }
     }
     ```
2. **Dedicated Save Button**: Added `Icons.Default.Save` in the top action bar for explicit 1-tap saving.
3. **Clickable Unsaved Badge**: Tapping the `UNSAVED` badge now triggers the save flow immediately and changes to `SAVED`.
4. **Physical Disk Target Resolution**:
   - `syncSingleFileToDisk()` in `ProjectRepository.kt` now checks `project.path`. If a real filesystem directory exists (e.g. `/sdcard/...` or workspace directory), it writes directly to disk rather than only internal app storage.

---

## 7. Workspace File Explorer — 60fps & Quick Actions

### Problems Resolved
- Lazy list scrolling stuttered significantly due to an artificial `delay(minOf(index, 12) * 40L)` delay and `AnimatedVisibility` wrapper on each file item.
- `.metadata`, `.DS_Store`, and system cache folders cluttered the explorer.
- No quick way to create files/folders without finding and long-pressing an existing item.

### Enhancements Made
1. **60fps Native Scrolling**:
   - Removed artificial per-item delays and layout-animating wrappers.
   - Employed stable item keys (`flatNode.node.id`) for instant, hitch-free scrolling.
2. **Header Quick Action Toolbar**:
   - **New File (`NoteAdd`)**: Prompts for file name; defaults to root if no folder is selected.
   - **New Folder (`CreateNewFolder`)**: Prompts for folder name.
   - **Collapse All (`UnfoldLess`)**: Clears expanded folder IDs set with one tap.
   - **Search / Filter Toggle (`Search`)**: Toggles the real-time search input bar.
   - **Paste & Deselect**: Quick actions for file cut/copy clipboard operations.
3. **Real-Time Search Bar**:
   - Live filter input with instant matching. Automatically expands parent folders of matching files.
4. **Hidden File Sanitization**:
   - Excluded `.metadata`, `.DS_Store`, and internal system artifacts from file listings.

---

## 8. Opened Files Dropdown Navigation

### Problem & Motivation
On compact screens, horizontal tab bars either cut off filenames or require awkward horizontal scrolling.

### Solution
- The active file name in `EditorTopBar` is now clickable and accompanied by an expandable arrow chevron.
- Tapping opens a quick navigation dropdown showing:
  - List of all currently opened tabs.
  - Active file indicator (accent highlight).
  - Unsaved modifications dot (`●`).
  - File extension icon and formatted name.
  - Per-tab close button (`✕`).
  - **"Close All Files"** footer action.

---

## 9. Projects Dashboard & Terminal Ergonomics

### Enhancements Made
1. **Optimized Project Scanning**:
   - Stopped triggering full filesystem scans every time the dashboard is loaded.
   - Cached project metadata to improve cold-start speed and avoid freezing lower-end devices.
2. **Visual Consistency (Card Borders)**:
   - Adjusted the border radius and padding of the "Local System" card to precisely match the "GitHub" and "Templates" cards.
3. **Overview Cards Modernization**:
   - Removed the 3 non-functional hardware boxes ("Storage Used", "RAM Allocated", "Active Sessions").
   - Added a **"Continue Working"** card that displays the last opened project, file count, and last modified timestamp for instant 1-tap resuming.
4. **Terminal / Shell Layout**:
   - Adjusted the shell tab ergonomics to full-height terminal layout (Termux style), eliminating dead space at the bottom.

---

## 10. React Porting Architecture & Component Mapping

To assist the CEO and frontend team with porting these features to the React legacy version, here is the direct architecture mapping:

| Android / Jetpack Compose Component | React Equivalent Component / Concept | Recommended Libraries / Implementation Strategy |
| :--- | :--- | :--- |
| `LarryProviders.kt` & `SettingsStore.kt` | `src/config/aiProviders.ts` | TypeScript enum/object containing provider endpoints and 2026 model lists. |
| `AiViewModel.kt` (Streaming SSE) | `src/hooks/useAiChat.ts` | Use `fetch()` with `ReadableStream` or `@microsoft/fetch-event-source` for chunk streaming. |
| `McpRepository.kt` & `McpModels.kt` | `src/services/mcpClient.ts` | Use `@modelcontextprotocol/sdk` or custom HTTP/SSE client; store configs in `localStorage` or `IndexedDB`. |
| `SkillsRepository.kt` (.md import) | `src/services/skillsService.ts` | Use `gray-matter` or a regex to parse YAML frontmatter from `.md` files uploaded via `<input type="file" accept=".md">`. |
| `PluginManager.kt` (5-stage install) | `src/services/pluginInstaller.ts` | State machine: `idle` → `resolving` → `downloading` → `extracting` → `configuring` → `activated`. Use `JSZip` to extract `.vsix` packages. |
| `PluginsPanel.kt` (Progress & Logs) | `src/components/plugins/PluginsModal.tsx` | Progress bar component + live monospace log array displayed in a modal terminal or collapsible drawer. |
| `EditorScreen.kt` (Auto-save) | `src/components/editor/EditorContainer.tsx` | Monaco Editor (`@monaco-editor/react`). Trigger save on `onBlur`, tab unmount, and `window.addEventListener('beforeunload', ...)`. |
| `FileExplorerPanel.kt` (60fps tree) | `src/components/explorer/FileTree.tsx` | Use `@tanstack/react-virtual` or `react-window` for virtualized rendering to ensure 60fps tree performance. |
| `EditorTopBar.kt` (Tabs dropdown) | `src/components/editor/OpenFilesDropdown.tsx` | Radix UI Popover / Dropdown Menu (`@radix-ui/react-dropdown-menu`) for accessible tab switching. |
| `ProjectsDashboard.kt` (Continue card) | `src/components/dashboard/Dashboard.tsx` | Save `lastOpenedProjectId` in `localStorage`. Render quick resume card at the top. |

---

## Verification & Release
- **Compilation**: Clean compilation with `./gradlew compileDebugKotlin` (Build exit code 0).
- **Packaging**: Signed release APK generated via `./gradlew assembleRelease`.
- **Target Device**: Shared directly to device **INI** via KDE Connect.
