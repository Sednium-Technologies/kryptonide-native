# Kryptonide Marketing Short Video Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and render a high-energy, responsive 36.0-second (1080 frames @ 30fps) marketing short launch video for Kryptonide in Remotion, with dual exports for 9:16 Vertical Short (1080 × 1920) and 16:9 Widescreen (1920 × 1080).

**Architecture:** A modular Remotion 4.x project located at `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion`. It uses spring physics for silky transitions, a unified responsive timeline with 5 distinct scenes, an audio engine with speech, synthesized ambient sound design, ducking, and word-by-word kinetic captions.

**Tech Stack:** React 19 / TypeScript, Remotion 4.0.535, Lucide-react icons, Chrome Headless Shell (Puppeteer), FFmpeg.

**Spec:** [`docs/superpowers/specs/2026-10-16-kryptonide-marketing-video-design.md`](file:///home/ankush/IdeaProjects/KryptonIDE-Main/docs/superpowers/specs/2026-10-16-kryptonide-marketing-video-design.md)

## Global Constraints
- Target directory: `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion`.
- Brand Name: "Kryptonide" (single word, pronounced together).
- Frame rate: 30 fps.
- Total Duration: exactly 1080 frames (36.00 seconds).
- Compositions: `KryptonideLaunchVertical` (1080 × 1920) and `KryptonideLaunchHorizontal` (1920 × 1080).
- Color Tokens: Charcoal Base `#2A2529`, Pale Ivory `#F3F0E7`, Emerald `#10B981`, Cyan `#38BDF8`, Violet `#A78BFA`, Amber `#F59E0B`.

---

### Task 1: Environment, Asset Pipeline & Audio Setup

**Files:**
- Create: `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion/scripts/generate-audio.mjs`
- Create: `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion/public/audio/scenes.json`
- Test: Verify generated audio files in `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion/public/audio`

**Interfaces:**
- Produces: `public/audio/sound-design.wav`, `public/audio/voiceover.mp3`, `public/audio/scenes.json` for scene timing and captions.

- [ ] **Step 1: Create the scene timeline data file**
  Create `public/audio/scenes.json` specifying the 5 scenes, their frame boundaries, durations, narration script, and word timings.

- [ ] **Step 2: Create deterministic audio synthesizer script**
  Create `scripts/generate-audio.mjs` using pure Node.js Float32Array synthesis to generate whooshes, UI typing clicks, sub-bass hits, and chord accents synced to the 5 scene boundaries, and output `public/audio/sound-design.wav`. Also assemble/place voiceover narration in `public/audio/voiceover.mp3`.

- [ ] **Step 3: Run audio generator script**
  Run: `cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && node scripts/generate-audio.mjs`
  Expected: Generated `sound-design.wav` and verified audio files in `public/audio`.

- [ ] **Step 4: Verify audio presence and durations with ffprobe**
  Run: `ffprobe -v error -show_entries format=duration -of csv=p=0 /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion/public/audio/sound-design.wav`
  Expected: Outputs `36.000000`.

---

### Task 2: Core Theme Tokens, Timeline & Responsive Layout System

**Files:**
- Create: `src/theme.ts`
- Create: `src/timeline.ts`
- Create: `src/types.ts`
- Create: `src/useResponsiveLayout.ts`

**Interfaces:**
- Produces:
  - `theme`: color constants (`Charcoal`, `Ivory`, `Emerald`, `Cyan`, etc.) and typography styles.
  - `SCENES`: array of scene metadata with frame ranges (`start`, `duration`, `title`, `kicker`, `caption`).
  - `useResponsiveLayout()`: hook returning `{ isVertical, width, height, scale, contentPadding }`.

- [ ] **Step 1: Write `src/theme.ts`**
  Export modern studio colors and font styles matching Kryptonide's dark theme:
  `Charcoal = '#2A2529'`, `Ivory = '#F3F0E7'`, `Surface = '#363134'`, `Border = '#4E4A4B'`, `Emerald = '#10B981'`, `Cyan = '#38BDF8'`, `Amber = '#F59E0B'`, `Violet = '#A78BFA'`.

- [ ] **Step 2: Write `src/types.ts` and `src/timeline.ts`**
  Define `SceneConfig` type and export `SCENES` matching the 1080-frame timeline:
  - Scene 1: Frames 0 to 165 (165f)
  - Scene 2: Frames 165 to 360 (195f)
  - Scene 3: Frames 360 to 585 (225f)
  - Scene 4: Frames 585 to 825 (240f)
  - Scene 5: Frames 825 to 1080 (255f)

- [ ] **Step 3: Write `src/useResponsiveLayout.ts`**
  Implement hook using Remotion's `useVideoConfig()` to compute layout dimensions for both 9:16 (1080x1920) and 16:9 (1920x1080).

- [ ] **Step 4: Typecheck**
  Run: `cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && npx tsc --noEmit`
  Expected: PASS with exit code 0.

---

### Task 3: Shared Components — Device Mockup, Kinetic Captions & Badges

**Files:**
- Create: `src/components/DeviceMockup.tsx`
- Create: `src/components/KineticCaptions.tsx`
- Create: `src/components/GlowingBadge.tsx`
- Create: `src/components/HeaderOverlay.tsx`

**Interfaces:**
- Produces:
  - `<DeviceMockup>`: 3D-angled mobile phone shell with status bar, dynamic notch, glass reflection, and children slot.
  - `<KineticCaptions>`: High-contrast word-by-word kinetic subtitle pill bar.
  - `<HeaderOverlay>`: Standardized scene kicker + title animation.

- [ ] **Step 1: Implement `<DeviceMockup.tsx>`**
  Create mobile bezel with curved titanium/slate corners, speaker notch, soft drop shadow (`boxShadow: 0 30px 90px rgba(0,0,0,0.6)`), and inner screen container.

- [ ] **Step 2: Implement `<KineticCaptions.tsx>`**
  Compute current active word index based on scene frame progress, applying scale pop (`scale(1.15)`) and bold Ivory highlight to active words.

- [ ] **Step 3: Implement `<HeaderOverlay.tsx>`**
  Animate kicker pill and bold tagline with staggered spring entrances.

- [ ] **Step 4: Typecheck**
  Run: `cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && npx tsc --noEmit`
  Expected: PASS with exit code 0.

---

### Task 4: Scene 1 — The Hook (`Scene1Hook.tsx`)

**Files:**
- Create: `src/scenes/Scene1Hook.tsx`

**Interfaces:**
- Consumes: `<DeviceMockup>`, `<HeaderOverlay>`, `<KineticCaptions>`, `theme`.
- Produces: Scene 1 component (165 frames) with floating phone entrance, matrix grid lines, and bold value proposition.

- [ ] **Step 1: Implement `Scene1Hook.tsx`**
  - Background: Radial gradient with faint cyber grid lines.
  - Phone Entrance: Uses `spring()` to glide device into view with a 3D tilt (`rotateY(-8deg) rotateX(6deg)`).
  - Screen Content: Shows Kryptonide IDE welcome screen with project cards ("FastAPI Backend", "React + Vite", "Android Native").
  - Launch badge: "Kryptonide 2.0" pill badge pulsing into position.

- [ ] **Step 2: Verify Scene 1 frame rendering**
  Run: `npx remotion still src/index.ts KryptonideLaunchVertical renders/test-s1.png --frame=60`
  Expected: Generates `renders/test-s1.png` showing clean 3D phone mockup and typography.

---

### Task 5: Scene 2 — Real Runtimes & Terminal (`Scene2Runtimes.tsx`)

**Files:**
- Create: `src/scenes/Scene2Runtimes.tsx`
- Create: `src/components/TerminalView.tsx`

**Interfaces:**
- Consumes: `TerminalView`, `theme`, `useResponsiveLayout`.
- Produces: Scene 2 component (195 frames) showing live terminal execution.

- [ ] **Step 1: Implement `TerminalView.tsx`**
  - Window header with macOS/Android traffic light dots.
  - Monospace command prompt: `~/my-project $ `
  - Simulated typing: `npm run dev` and `python3 -m uvicorn main:app --port 8000`.
  - Animated output lines: Vite v5.0.0 ready in 184ms, Local: `http://localhost:5173/`.
  - Package pills: `node_modules` (no-bin-links), `python packages` (target pip).

- [ ] **Step 2: Implement `Scene2Runtimes.tsx`**
  - Smooth camera zoom into the terminal console.
  - Glowing runtime chips floating alongside the console (`Node.js`, `Python 3`, `Bash`, `FastAPI`).

- [ ] **Step 3: Verify Scene 2 frame rendering**
  Run: `npx remotion still src/index.ts KryptonideLaunchVertical renders/test-s2.png --frame=250`
  Expected: Generates `renders/test-s2.png` with rendered terminal output.

---

### Task 6: Scene 3 — Agentic AI Assistant (`Scene3AgenticAi.tsx`)

**Files:**
- Create: `src/scenes/Scene3AgenticAi.tsx`
- Create: `src/components/AiPanelMock.tsx`

**Interfaces:**
- Consumes: `<DeviceMockup>`, `theme`.
- Produces: Scene 3 component (225 frames) showcasing the redesigned AI panel with BYOK chips and live patch apply.

- [ ] **Step 1: Implement `AiPanelMock.tsx`**
  - Top header: "AI Assistant" with glowing sparkle icon and session drawer toggle.
  - Model chips carousel: `Groq Llama 3`, `Claude 3.5 Sonnet`, `GPT-4o`, `DeepSeek V3`.
  - Chat bubble: "Refactor `auth.py` to use async JWT verification."
  - Streaming code diff card with green inserted lines (`+ async def verify_token()`) and one-click "Apply to Editor" button.

- [ ] **Step 2: Implement `Scene3AgenticAi.tsx`**
  - Animate AI panel sliding into the mobile IDE frame.
  - Particle sparkles radiating from the AI button when patch is applied.

- [ ] **Step 3: Verify Scene 3 frame rendering**
  Run: `npx remotion still src/index.ts KryptonideLaunchVertical renders/test-s3.png --frame=450`
  Expected: Generates `renders/test-s3.png`.

---

### Task 7: Scene 4 — Pro Editor & GitHub Workflow (`Scene4EditorGit.tsx`)

**Files:**
- Create: `src/scenes/Scene4EditorGit.tsx`
- Create: `src/components/EditorMock.tsx`
- Create: `src/components/GitSheetMock.tsx`

**Interfaces:**
- Consumes: `<DeviceMockup>`, `theme`.
- Produces: Scene 4 component (240 frames) featuring the File Explorer tree, tactile symbol bar, and GitHub commit sheet.

- [ ] **Step 1: Implement `EditorMock.tsx`**
  - Left pane: File Explorer tree with subtle vertical indentation guides, folder chevrons, language icons (Kotlin violet, Python green, JSON yellow).
  - Center pane: Code editor with line numbers, active syntax highlighting, and unsaved amber dot.
  - Bottom: Tactile symbol shortcut bar (`{`, `}`, `=>`, `Tab`, `⌘`).

- [ ] **Step 2: Implement `GitSheetMock.tsx`**
  - Modal bottom sheet: Linked repo `AnkushDas4/KryptonIDE-main`, branch badge (`main`), commit message input: `"feat: add live agentic AI patch"`.
  - Commit & Push action button triggering success checkmark.

- [ ] **Step 3: Implement `Scene4EditorGit.tsx`**
  - Transitions smoothly from editor view to the GitHub sheet sliding up.

- [ ] **Step 4: Verify Scene 4 frame rendering**
  Run: `npx remotion still src/index.ts KryptonideLaunchVertical renders/test-s4.png --frame=700`
  Expected: Generates `renders/test-s4.png`.

---

### Task 8: Scene 5 — Outro & Call To Action (`Scene5Outro.tsx`)

**Files:**
- Create: `src/scenes/Scene5Outro.tsx`
- Create: `src/components/OutroCard.tsx`

**Interfaces:**
- Consumes: `theme`, `useResponsiveLayout`.
- Produces: Scene 5 component (255 frames) with glowing Kryptonide branding and download CTA.

- [ ] **Step 1: Implement `OutroCard.tsx`**
  - Centerpiece: High-res Kryptonide app icon with pulsing emerald & cyan aura rings.
  - Bold Typography: "Kryptonide" in Pale Ivory with subtle letter-spacing.
  - Subtitle: "The Mobile IDE Engineered for Real Code."
  - Action Pill: "Download for Android" with download icon.
  - URL Pill: `kryptonide.sednium.com` and GitHub badge.

- [ ] **Step 2: Implement `Scene5Outro.tsx`**
  - Smooth camera pullback displaying device alongside feature badges (`Android Native`, `BYOK AI`, `Full Terminal`, `Zero Cloud Bloat`).

- [ ] **Step 3: Verify Scene 5 frame rendering**
  Run: `npx remotion still src/index.ts KryptonideLaunchVertical renders/test-s5.png --frame=950`
  Expected: Generates `renders/test-s5.png`.

---

### Task 9: Dual Composition Assembly in `Root.tsx` & Audio Sync

**Files:**
- Modify: `src/Root.tsx`
- Create: `src/KryptonideVideo.tsx`
- Modify: `src/index.ts`

**Interfaces:**
- Produces:
  - `<Composition id="KryptonideLaunchVertical" ... />` (1080 × 1920, 1080 frames, 30fps)
  - `<Composition id="KryptonideLaunchHorizontal" ... />` (1920 × 1080, 1080 frames, 30fps)
  - Integrated `<Audio>` tags for `sound-design.wav` and `voiceover.mp3` with ducking.

- [ ] **Step 1: Implement `src/KryptonideVideo.tsx`**
  Wrap scenes in Remotion's `<Series>`, sequential scene transitions, `<Audio>` element with ducking configuration, and dynamic captions overlay.

- [ ] **Step 2: Update `src/Root.tsx`**
  Register both `KryptonideLaunchVertical` and `KryptonideLaunchHorizontal` compositions referencing `<KryptonideVideo>`.

- [ ] **Step 3: Full Project Typecheck**
  Run: `cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && npx tsc --noEmit`
  Expected: Clean compilation with 0 errors.

---

### Task 10: Dual Render Execution & Quality Verification

**Files:**
- Output: `renders/kryptonide-short-vertical.mp4`
- Output: `renders/kryptonide-short-horizontal.mp4`

- [ ] **Step 1: Render Vertical Short (1080 × 1920)**
  Run:
  ```bash
  cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && npx remotion render src/index.ts KryptonideLaunchVertical renders/kryptonide-short-vertical.mp4
  ```
  Expected: Successful render in ~30–60 seconds, exit code 0.

- [ ] **Step 2: Render Horizontal Widescreen (1920 × 1080)**
  Run:
  ```bash
  cd /home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion && npx remotion render src/index.ts KryptonideLaunchHorizontal renders/kryptonide-short-horizontal.mp4
  ```
  Expected: Successful render in ~30–60 seconds, exit code 0.

- [ ] **Step 3: Verify video files with ffprobe**
  Run:
  ```bash
  ffprobe -v error -show_entries stream=width,height,r_frame_rate,duration -of default=noprint_wrappers=1 renders/kryptonide-short-vertical.mp4
  ffprobe -v error -show_entries stream=width,height,r_frame_rate,duration -of default=noprint_wrappers=1 renders/kryptonide-short-horizontal.mp4
  ```
  Expected: Vertical shows 1080x1920, 30 fps, ~36.0s. Horizontal shows 1920x1080, 30 fps, ~36.0s.
