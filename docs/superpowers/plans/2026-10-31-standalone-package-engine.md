# Standalone Package Engine (Zero Termux Dependency) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Kryptonide 100% self-contained by eliminating any dependency on or nudges toward Termux, providing a pure in-app package engine (`npm`, `pip`, `node`, `python`, `git`) and runtime preview.

**Architecture:** 
1. Upgrade `KryptonShellEngine` to act as a native package manager and runtime coordinator: query NPM Registry and PyPI APIs directly over HTTPS, manage `package.json`, `node_modules`, `requirements.txt`, and pure-Python packages in `/storage/emulated/0/Kryptonide/packages/`.
2. Expand `RenderEngine` to automatically resolve npm dependencies from `package.json` into the live web preview via dynamic CDN/ESM injection.
3. Clean all Termux nudges, banners, tips, and error messages from `TerminalViewModel`, `TerminalPanel`, `PackageInstaller`, and `RenderEngine`.

**Tech Stack:** Kotlin, Jetpack Compose, OkHttp/HttpURLConnection, Android WebView, JGit, JSON.

**Spec:** Direct user instruction: "yes i dont want to rely on termux for this."

## Global Constraints
- Zero reliance on Termux: Kryptonide must never require, prompt, or nudge users to install or open Termux.
- Android non-exec safe: All packages written to external storage must avoid symlinks and executable binaries.
- Preserve uppercase `KRYPTONIDE` on splash and project loading screens.
- Persist chat history in `/sdcard/Kryptonide/chats/` and `<project>/.kryptonide/chat.json`.
- Live emulator verification before completion.

---

### Task 1: Standalone In-App NPM Engine (Zero Termux Dependency)

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/KryptonShellEngine.kt`

- [ ] **Step 1: Enhance `executeNpm` to be pure native by default**
  - Make native Kryptonide NPM engine the primary executor for all npm commands (`install`, `uninstall`, `rm`, `update`, `run`, `list`, `init`, `audit`, `test`, `help`).
  - Never dispatch to Termux unless explicitly prefixed with `termux npm`.
  - Support multiple packages in single command: `npm install framer-motion lucide-react clsx`.
  - Support `npm uninstall <pkg...>` / `npm rm <pkg...>`.
  - Support `npm update [pkg...]`.
  - Support `npm run dev` / `npm start` with Vite / Next.js web preview simulation.
  - Support `npm list` / `npm ls` with formatted tree.
  - Support `npm init [-y]`.
  - Support `npm audit` and `npm test`.

- [ ] **Step 2: Update help and command registry in `KryptonShellEngine.kt`**
  - Put `Package Managers & Runtimes (Pure In-App, Zero Setup)` at the top of the help banner.
  - Relegate `termux` to optional external bridge at the very bottom.
  - Remove any "Install Termux (pkg install nodejs)" error message.

---

### Task 2: Standalone In-App PyPI / PIP Engine & Python/Node Execution

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/KryptonShellEngine.kt`

- [ ] **Step 1: Implement pure native PyPI client in `executePip`**
  - Implement HTTPS query to `https://pypi.org/pypi/<pkg>/json`.
  - Extract latest version and download pure-Python wheel (`.whl`) or source archive.
  - Unzip wheel contents directly into `/storage/emulated/0/Kryptonide/packages/python/`.
  - Implement `pip list`, `pip show <pkg>`, `pip uninstall <pkg>`, `pip freeze`.
  - Record installed packages into `requirements.txt`.

- [ ] **Step 2: Implement self-contained `executeNode` and `executePython` fallbacks**
  - For `node`: run JS code / scripts via Android JavaScript evaluation or console runner when system binary is absent.
  - For `python`: execute or validate script with AST/syntax report without asking to install Termux.
  - Remove all "Install Termux (pkg install python/nodejs)" errors.

---

### Task 3: Complete Removal of All Termux Nudges, Warnings, and Prompts

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/TerminalViewModel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/terminal/TerminalPanel.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/packages/PackageInstaller.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderEngine.kt`

- [ ] **Step 1: Clean `TerminalViewModel.kt`**
  - Remove `Tip: Install Termux from F-Droid for full package manager (npm, python, clang)`.
  - Welcome banner: `Kryptonide Shell active. Standalone package engine (npm, pip, git) ready.`

- [ ] **Step 2: Clean `TerminalPanel.kt`**
  - Replace `TERMUX` quick category chip with `PACKAGES`.
  - Add quick package commands: `npm install`, `npm run dev`, `npm list`, `pip install`, `pip list`, `node`, `python`.
  - In `TerminalEmptyState`: update title to "Kryptonide Terminal", update description to "Full POSIX commands, native package manager (npm/pip), and built-in Git." with "✓ Pure standalone engine ready — no external dependencies."

- [ ] **Step 3: Clean `PackageInstaller.kt` & `RenderEngine.kt`**
  - Remove all Termux tips and hints from `PackageInstaller.kt`.
  - Update `runtimeInstallHint` in `RenderEngine.kt` to reference Kryptonide standalone engine.

---

### Task 4: Dynamic Web Preview NPM Package Loading

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/ui/render/RenderEngine.kt`

- [ ] **Step 1: Dynamic CDN / ESM dependency resolution**
  - In `prepareReactHtml` and `prepareNextHtml`, inspect `package.json` dependencies.
  - Inject CDN scripts or ESM importmaps for installed packages.
  - Ensure `framer-motion`, `lucide-react`, and any npm package run smoothly in web preview.

---

### Task 5: Unit Tests, Verification, APK Build & Device Delivery

- [ ] **Step 1: Run unit tests**
  - Execute `./gradlew testDebugUnitTest`.

- [ ] **Step 2: Build release APK**
  - Execute `./gradlew assembleRelease`.

- [ ] **Step 3: Live verification on emulator**
  - Install updated APK on `emulator-5554`.
  - Test terminal: verify zero Termux mentions, verify `PACKAGES` tab.
  - Test `npm install framer-motion`, `npm run dev`, `npm list`, `npm rm framer-motion`.
  - Test `pip install requests`, `pip list`.
  - Take emulator screenshots as evidence.

- [ ] **Step 4: Send release APK to user phone INI**
  - Deliver via `kdeconnect-cli --share app-release.apk --device d13cd17d9cb54283bb0bc4cf62cd32b8`.
