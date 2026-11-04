# Needle GitHub Clone, Disconnect, and Storage Boundary Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the repo unlink bug, add instant repo cloning via Needle and JGit, persist GitHub repo connections to AppDatabase, and enforce strict containment within `/storage/emulated/0/Kryptonide`.

**Architecture:** Update `NeedleToolDispatcher` to recognize disconnect and clone intents before connection matching, register `github_disconnect` and `github_clone` in `GitHubToolSet` utilizing JGit, update `GitViewModel` to persist linked repo state to `AppDatabase`, and validate all tool/file/shell operations against `StorageUtils.getKryptonideRoot()`.

**Tech Stack:** Kotlin, Jetpack Compose, JGit, Android SDK, JUnit.

## Global Constraints
- All file operations, clones, and navigation MUST strictly remain inside `/storage/emulated/0/Kryptonide`.
- Direct action tools (`github_connect`, `github_disconnect`, `github_clone`, `github_push`, `github_status`, `execute_terminal_command`) must return immediately without triggering an LLM continuation loop.
- All unit tests must pass via `./gradlew testDebugUnitTest`.

---

### Task 1: Update NeedleToolDispatcher
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/pocket/NeedleToolDispatcher.kt`
- Test: `app/src/test/java/com/sednium/kryptonide/agent/pocket/PocketModelsTest.kt`

- [ ] Add `github_disconnect` check before `github_connect`.
- [ ] Add `github_clone` check with URL and target folder extraction.
- [ ] Add `unlink`, `disconnect`, `un-link`, `dis-connect`, `detach`, `remove`, `clone` to `stopWords` in `extractRepoName`.
- [ ] Add helper functions `extractCloneUrl(prompt: String): String?` and `extractCloneFolder(prompt: String): String?`.

### Task 2: Implement GitHub Tools & Storage Boundary
**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/tools/GitHubToolSet.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/tools/FsToolSet.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/KryptonShellEngine.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/ai/AiViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/git/GitViewModel.kt`

- [ ] Register `github_disconnect` in `GitHubToolSet.kt`.
- [ ] Register `github_clone` in `GitHubToolSet.kt` using JGit with sandbox checking against `StorageUtils.getKryptonideRoot()`.
- [ ] Update `isDirectActionTool` in `AiViewModel.kt` to include `github_disconnect` and `github_clone`.
- [ ] Wire `setLinkedRepo` in `AiViewModel.kt` to call `gitVm?.unlinkRepo()` when `repo == null`.
- [ ] Update `GitViewModel.kt` `linkRepo` and `unlinkRepo` to persist changes to `AppDatabase`.
- [ ] Ensure `FsToolSet.kt` and `KryptonShellEngine.kt` cannot navigate outside `StorageUtils.getKryptonideRoot()`.

### Task 3: Test, Build & Deliver
**Files:**
- Modify: `app/src/test/java/com/sednium/kryptonide/agent/pocket/PocketModelsTest.kt`

- [ ] Add test cases for unlink, disconnect, and clone in `PocketModelsTest.kt`.
- [ ] Run `./gradlew testDebugUnitTest`.
- [ ] Build release APK with `./gradlew assembleRelease`.
- [ ] Share release APK via KDE Connect to device `INI`.
