# Fix AI Agent Send Button, Add Project Card Renaming, and Exclude KryptonIDE Internal Directories Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
1. Fix responsiveness, keyboard interaction, and touch targets of the send button in the AI panel / Agent mode (`LarryAIPanel.kt`).
2. Add a project card renaming action and modal dialog to `ProjectsDashboard.kt` with disk and database synchronization in `ProjectRepository.kt` and `ProjectsViewModel.kt`.
3. Exclude KryptonIDE's internal directories (`mcp`, `skills`, `plugins`, `packages`, `.git`, `.metadata`) from project scanning, watcher discovery, Room database, and the dashboard project list.

**Architecture:**
- **AI Agent Send Button**: Replace restrictive Material 3 `IconButton(32.dp)` with a clean, high-contrast, touch-accessible `Box(36.dp)` with `Role.Button`. Bind `KeyboardOptions(imeAction = ImeAction.Send)` and `KeyboardActions(onSend = ...)` to `BasicTextField`. Apply `Modifier.imePadding()` to the composer container to ensure the input bar and send button float smoothly above the virtual keyboard. Parameterize `buildPrompt(mode)` so agent quick actions execute synchronously with the intended mode guidance.
- **Project Renaming**: Extend `ProjectRepository.renameProject(id, newName)` to safely rename the disk folder on external storage (`/storage/emulated/0/KryptonIDE/<old>` -> `<new>`), update the database `Project` and root `FileNode` entities, and update `FileWatcherService`. Add `viewModel.renameProject(id, newName)`. On `ProjectsDashboard.kt`, add a rename button (`Lucide.Pencil`) to `ProjectCard` and render a modern `RenameProjectDialog`.
- **Exclude Internal Dirs**: Define `KRYPTON_INTERNAL_DIRS = setOf("mcp", "skills", "plugins", "packages", ".git", ".metadata", ".nomedia", ".system_generated")`. Filter out these internal storage folders across `ProjectRepository.getAllProjects()`, `ProjectRepository.importProjectFromDisk()`, `ProjectsViewModel.loadProjects()`, `FileWatcherService.handleFsEvent()`, and `ProjectsDashboard.displayProjects`. Purge any accidental internal directory records from Room DB.

**Tech Stack:**
- Kotlin, Jetpack Compose, Material 3
- Room Database (`AppDatabase`)
- Lucide Android Icons (`com.composables:icons-lucide-android:1.1.0`)
- Android FileObserver & Coroutines

**Spec:**
User request:
"fix the send button in the ai agents tab. add rename project cards. exlude the kryptonides own project file directory mcp skills and plugns from the project lists"

## Global Constraints
- Preserve Lucide icons exclusively (`com.composables.icons.lucide.*`).
- Keep all Bring-Your-Own-Key (BYOK) AI providers intact.
- Safe file operations: do not destroy user data during folder renaming.
- Fast compilation with `./gradlew compileDebugKotlin`.

---

### Task 1: Exclude KryptonIDE Internal Directories from Project Lists

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/data/repository/ProjectRepository.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsDashboard.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/service/FileWatcherService.kt`

**Interfaces:**
- Produces: `val KRYPTON_INTERNAL_DIRS = setOf("mcp", "skills", "plugins", "packages", ".git", ".metadata", ".nomedia", ".system_generated")`
- Ensures: `getAllProjects()` purges internal entries from Room DB and returns only user projects.
- Ensures: `loadProjects()` and `importProjectFromDisk()` ignore all internal dirs.

- [ ] **Step 1: Update `ProjectRepository.kt` with internal directory exclusion & DB purge**
  Add `KRYPTON_INTERNAL_DIRS`. In `getAllProjects()`, purge any project where `name.lowercase() in KRYPTON_INTERNAL_DIRS` or `name.startsWith(".")`. In `importProjectFromDisk()`, check if `projectName.lowercase() in KRYPTON_INTERNAL_DIRS` and return immediately without saving to DB.

- [ ] **Step 2: Update `ProjectsViewModel.kt` disk scanner & watcher**
  In `loadProjects()`, filter both `dbProjects` and `diskFolders` with `KRYPTON_INTERNAL_DIRS`. In `watchForNewProjects()`, discard any `ProjectDiscovered` event for internal directories.

- [ ] **Step 3: Update `FileWatcherService.kt` & `ProjectsDashboard.kt`**
  In `FileWatcherService.handleFsEvent()`, ignore paths containing internal directory roots. In `ProjectsDashboard.kt`, add defensive filtering in `displayProjects` so internal directories never show up in UI lists.

- [ ] **Step 4: Verify compilation**
  Run `./gradlew compileDebugKotlin`.

---

### Task 2: Implement Project Card Renaming (Disk, DB & UI)

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/data/repository/ProjectRepository.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/projects/ProjectsDashboard.kt`

**Interfaces:**
- `ProjectRepository.renameProject(id: String, newName: String): Boolean`
- `ProjectsViewModel.renameProject(id: String, newName: String, onComplete: ((Boolean) -> Unit)? = null)`
- `ProjectCard(..., onRename: () -> Unit)`
- `RenameProjectDialog(project: Project, onDismiss: () -> Unit, onRename: (String) -> Unit)`

- [ ] **Step 1: Implement `ProjectRepository.renameProject`**
  Implement safe rename on disk:
  - Validate new name: not blank, no illegal characters, not in `KRYPTON_INTERNAL_DIRS`.
  - Check target destination does not already exist.
  - Rename old directory to new directory (with fallback copy/delete if needed).
  - Update `FileWatcherService` (stop watching old name, start watching new name).
  - Update `Project` in Room DB.
  - Update root `FileNode` in Room DB.

- [ ] **Step 2: Update `ProjectsViewModel.renameProject`**
  Call repository's `renameProject` within `viewModelScope.launch`, refresh projects via `loadProjects()`, and invoke completion callback.

- [ ] **Step 3: Add Rename button & dialog in `ProjectsDashboard.kt`**
  - In `ProjectCard`, add a vertical divider and `IconButton` with `Lucide.Pencil` (13.dp) between Open and Delete.
  - Add state `var projectToRename by remember { mutableStateOf<Project?>(null) }`.
  - Create `@Composable fun RenameProjectDialog(project: Project, onDismiss: () -> Unit, onRename: (String) -> Unit)` styled with `SurfaceDark`, `TextPrimary`, `AccentLight`, with input validation and haptic feedback.
  - Wire the dialog to invoke `viewModel.renameProject`.

- [ ] **Step 4: Verify compilation**
  Run `./gradlew compileDebugKotlin`.

---

### Task 3: Fix Send Button in AI Panel & Agent Mode

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/editor/LarryAIPanel.kt`

**Interfaces:**
- `buildPrompt(targetMode: String = aiMode): String`
- `BasicTextField` with `KeyboardOptions` and `KeyboardActions`
- Touch-accessible Send button with `Box` and `Role.Button`
- `Modifier.imePadding()` on input container

- [ ] **Step 1: Add `imePadding()` to input container in `LarryAIPanel.kt`**
  At the composer Column (around line 966), add `.imePadding()` so the text field, suggestion pills, and send button remain above the Android software keyboard when focused.

- [ ] **Step 2: Add keyboard actions to `BasicTextField`**
  Configure `BasicTextField`:
  ```kotlin
  keyboardOptions = KeyboardOptions(
      imeAction = ImeAction.Send,
      capitalization = KeyboardCapitalization.Sentences
  ),
  keyboardActions = KeyboardActions(
      onSend = {
          if (canSend) {
              val prompt = buildPrompt()
              aiVm.sendMessage(inputText.trim(), prompt)
              inputText = ""
          }
      }
  )
  ```

- [ ] **Step 3: Upgrade Send & Stop buttons in composer row**
  Replace restrictive `IconButton(size = 32.dp)` with a dedicated `Box` (`size = 36.dp`) that has `clip(CircleShape)`, explicit `clickable(enabled = canSend, role = Role.Button)`, and `Lucide.ArrowUp` (`size = 18.dp`), with Stop button similarly using `Box` (`size = 36.dp`) with `Lucide.Square`.

- [ ] **Step 4: Parameterize `buildPrompt` for agent quick actions**
  Make `buildPrompt(targetMode: String = aiMode): String` accept a parameter so `onQuickPrompt(prompt, preferredMode)` applies `preferredMode` immediately without waiting for Compose recomposition.

- [ ] **Step 5: Verify compilation**
  Run `./gradlew compileDebugKotlin`.

---

### Task 4: Full App Build, Emulator Verification & Live Testing

**Files:**
- Test target: Android Emulator `emulator-5554`

- [ ] **Step 1: Build debug APK**
  Run `./gradlew assembleDebug`.
- [ ] **Step 2: Start emulator & install APK**
  Launch emulator if not running, install debug APK with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 3: Verify internal directories exclusion**
  Launch app, capture screenshot of dashboard, confirm `mcp`, `skills`, `plugins`, `packages` are NOT listed in the projects list.
- [ ] **Step 4: Verify rename project card**
  Tap the pencil icon on a project card, test the Rename dialog, rename a project, and verify the name updates in real time on the card and disk.
- [ ] **Step 5: Verify AI agent send button**
  Open the project, switch to AI tab, switch mode to "Agent", type a prompt, tap the send button, and confirm it dispatches properly. Take screenshots for evidence.
