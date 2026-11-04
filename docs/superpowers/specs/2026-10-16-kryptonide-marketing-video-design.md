# Kryptonide — High-Energy Marketing Launch Short Video Design

- **Date**: 2026-10-16
- **Target**: Dual Composition — 9:16 Vertical Short (1080 × 1920) & 16:9 Widescreen (1920 × 1080)
- **Duration**: 36.0 seconds · 1080 frames @ 30fps
- **Framework**: Remotion 4.x (React + TypeScript)
- **Brand / Pronunciation**: **Kryptonide** (pronounced together as a single word)

---

## 1. Executive Summary & Goals

The goal is to replace the static legacy slideshow with a high-energy, modern marketing short video built entirely in Remotion. The video showcases Kryptonide not as a toy mobile editor, but as a full-stack engineering workstation running on Android.

Key outcomes:
1. **Responsive Dual Output**:
   - `KryptonideLaunchVertical` (1080 × 1920) for YouTube Shorts, Instagram Reels, TikTok, and mobile feeds.
   - `KryptonideLaunchHorizontal` (1920 × 1080) for desktop YouTube, landing page hero, and landscape presentations.
2. **High-Retention Audio & Motion Design**:
   - Concise, punchy tech voiceover synchronized with word-by-word kinetic captions.
   - Upbeat electronic synth backing track with automatic audio ducking during speech.
   - Tactile UI sound design (keystroke clicks, swoosh transitions, success chimes).
3. **Modern Studio Dark Aesthetic**:
   - Charcoal base (`#2A2529`) and Pale Ivory (`#F3F0E7`) with glowing developer accents (Emerald `#10B981`, Cyan `#38BDF8`, Violet `#A78BFA`, Amber `#F59E0B`).

---

## 2. Storyboard & Scene Timeline (1080 Frames · 36.0 Seconds)

| Scene | Name | Time Range | Frames | Focus & Key Visuals |
|---|---|---|---|---|
| **Scene 1** | The Hook | 0.0s – 5.5s | 0 – 165 (165 frames) | 3D phone device gliding into frame, glowing code grid lines, bold punchy question |
| **Scene 2** | Real Runtimes | 5.5s – 12.0s | 165 – 360 (195 frames) | Live terminal execution: `npm run dev` & `python3 -m uvicorn`, on-device server status |
| **Scene 3** | Agentic AI | 12.0s – 19.5s | 360 – 585 (225 frames) | Modern AI Assistant panel, BYOK chips (Groq, Claude, OpenAI), live streaming patch acceptance |
| **Scene 4** | Editor & Git | 19.5s – 27.5s | 585 – 825 (240 frames) | File tree explorer with indentation guides, tactile symbol bar, GitHub sync bottom sheet |
| **Scene 5** | Outro & CTA | 27.5s – 36.0s | 825 – 1080 (255 frames) | Kryptonide glowing icon, dual device presentation, call-to-action badge & website URL |

---

## 3. Scene Breakdown & Narration Script

### Scene 1: The Hook (0.0s – 5.5s · 165 frames)
- **Narration**: *"What if your mobile IDE wasn't a toy, but a full-stack engineering workstation?"*
- **Headlines**:
  - `Tagline`: "Desktop IDE Power. In Your Pocket."
  - `Kicker`: `// real architecture · zero compromise`
- **Visuals**:
  - Deep Charcoal canvas (`#2A2529`) with dynamic subtle grid lines.
  - Floating 3D perspective mobile phone mockup gliding up with spring physics (`damping: 18, stiffness: 90`).
  - Kryptonide logo and launch badge popping in with glowing aura.

### Scene 2: Real Runtimes & Terminal (5.5s – 12.0s · 195 frames)
- **Narration**: *"Run real Node.js and Python runtimes with shared packages, running Vite and FastAPI directly on-device."*
- **Headlines**:
  - `Tagline`: "Real Runtimes. Zero Emulation Lag."
  - `Kicker`: `Node.js · Python 3 · Bash · Vite · FastAPI`
- **Visuals**:
  - Mobile frame expands and centers into an interactive terminal console.
  - Animated character typing: `npm run dev` followed by server startup logs.
  - Green status pill indicator: `● localhost:5173 ready in 184ms`.
  - Floating runtime badges: `Python 3.11`, `Node v20`, `bash`.

### Scene 3: Agentic AI Assistant (12.0s – 19.5s · 225 frames)
- **Narration**: *"Bring your own keys. Ask AI to refactor code, generate full features, and apply patches in seconds."*
- **Headlines**:
  - `Tagline`: "Agentic AI Assistant. BYOK Privacy."
  - `Kicker`: `Claude · Groq · OpenAI · DeepSeek · Ollama`
- **Visuals**:
  - Modern studio AI panel sliding in with glowing assistant badge.
  - Model provider pills dynamically cycling: `Groq Llama-3`, `Claude 3.5 Sonnet`, `GPT-4o`, `DeepSeek V3`.
  - Live AI message stream: code diff preview with green added lines and red removed lines.
  - One-tap "Apply Patch" button with haptic glow and success checkmark.

### Scene 4: Pro Editor & GitHub Workflow (19.5s – 27.5s · 240 frames)
- **Narration**: *"Full tree explorer, tactile shortcut bar, and instant GitHub sync keep you in flow wherever you build."*
- **Headlines**:
  - `Tagline`: "Precision Editor & Git Sync."
  - `Kicker`: `Instant Git Push · Command Palette · File Tree`
- **Visuals**:
  - Editor split view showcasing the new File Tree Explorer with tree indentation guides, extension badges, and animated folder chevrons.
  - Tactile editor shortcut bar above soft keyboard with active symbols (`{`, `}`, `=>`, `\t`).
  - GitHub modal bottom sheet sliding up: repository selector, branch pill (`main`), and one-tap Commit & Push button with pulsing upload indicator.

### Scene 5: Outro & Call to Action (27.5s – 36.0s · 255 frames)
- **Narration**: *"Kryptonide. Precision in your pocket, clarity on your screen. Get started today."*
- **Headlines**:
  - `Tagline`: "Build Anywhere. Ship Everywhere."
  - `Kicker`: `kryptonide.sednium.com · Open Source on GitHub`
- **Visuals**:
  - Central 3D phone device frame flanked by code reflections.
  - Glowing Kryptonide app icon with radiating pulse waves.
  - Action button: "Download for Android" pill button with arrow icon.
  - Final branding lockup: `Kryptonide` in bold ivory typography, subtitle: `The Mobile IDE Engineered for Real Code`.

---

## 4. Technical Architecture

### 4.1 Directory Structure
The video project is housed in `/home/ankush/Videos/remotion/Kryptonide/KryptonIDE-main/remotion`:
```
remotion/
├── package.json
├── remotion.config.ts
├── tsconfig.json
├── public/
│   ├── audio/
│   │   ├── voiceover.mp3
│   │   ├── music.mp3
│   │   └── sfx/ (click.mp3, swoosh.mp3, chime.mp3)
│   └── assets/
│       ├── logo_512.png
│       └── app_icon.png
└── src/
    ├── index.ts
    ├── Root.tsx
    ├── theme.ts             # Charcoal, Ivory, Emerald, Cyan color tokens
    ├── timeline.ts          # Frame timestamps and scene boundaries
    ├── types.ts
    ├── components/
    │   ├── DeviceMockup.tsx # Sleek phone container (vertical & horizontal adaptive)
    │   ├── KineticCaptions.tsx # Synchronized word-pop subtitles
    │   ├── TerminalView.tsx # Real terminal code typing animation
    │   ├── AiPanelMock.tsx  # Glowing AI assistant & BYOK badge
    │   ├── EditorTreeMock.tsx # Upgraded file explorer & editor layout
    │   └── OutroCard.tsx    # Logo, pulsing aura, CTA button
    └── scenes/
        ├── Scene1Hook.tsx
        ├── Scene2Runtimes.tsx
        ├── Scene3AgenticAi.tsx
        ├── Scene4EditorGit.tsx
        └── Scene5Outro.tsx
```

### 4.2 Responsive Layout Adaptations
- **Vertical (1080 × 1920)**:
  - Phone device mockup takes center 60% of screen height.
  - Punchy kinetic captions positioned in the lower-third with high-contrast pill backdrops.
  - Header and kicker typography anchored at the top-third.
- **Horizontal (1920 × 1080)**:
  - 2-column layout: Left column contains large editorial typography, kicker, and word captions; Right column showcases the device frame / IDE panels at 1.1x scale.

### 4.3 Motion Physics & Transition Curves
- Entrances: `spring({ fps, frame: localFrame, config: { damping: 18, mass: 0.8, stiffness: 100 } })`.
- Scene Transitions: Continuous `Series` with 10-frame cross-fades and smooth scale-in ($0.96 \to 1.0$).
- Code Typing: Stepped typing using `interpolate()` clamped to character count.

---

## 5. Verification & Delivery Plan

1. **Typecheck & Linter**: Ensure TypeScript compiles cleanly (`tsc --noEmit`).
2. **Preview**: Verify both compositions in Remotion Studio (`npm run start` or `npx remotion studio`).
3. **Render Execution**:
   - `remotion render src/index.ts KryptonideLaunchVertical renders/kryptonide-short-vertical.mp4`
   - `remotion render src/index.ts KryptonideLaunchHorizontal renders/kryptonide-short-horizontal.mp4`
4. **Output Verification**:
   - Verify resolution (1080x1920 & 1920x1080), 30fps, 1080 frames, audio sync using `ffprobe`.
