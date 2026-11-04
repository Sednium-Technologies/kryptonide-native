# Unified Local Model (MiniCPM5-1B Thinking) & Hardware Acceleration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate KryptonIDE's on-device AI onto a single unified model (`MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking`) with native XML tool calling, `<think>` reasoning, and Sednium OORTY hardware acceleration (JNI big-core affinity, GPU compute, switchable Q8/Q4 KV caching).

**Architecture:** A unified GGUF runtime in `LocalGgufRunner` formats system prompts with MiniCPM5's native `<tools>` XML schema, binds worker threads to performance cores via `OortyNativeEngine` JNI (`sched_setaffinity`), manages switchable `Q8_0`/`Q4_0` KV cache quantization, and streams both internal reasoning `<think>` and structured `<function>` tool calls directly to the IDE.

**Tech Stack:** Kotlin, Android NDK (C++17, JNI, POSIX `sched_setaffinity`, `mmap`), GGUF / llama.cpp runtime, Kotlinx Serialization, JUnit 4.

**Spec:** [docs/superpowers/specs/2026-10-27-unified-local-model-minicpm5-hardware-acceleration-design.md](file:///home/ankush/IdeaProjects/KryptonIDE-Main/docs/superpowers/specs/2026-10-27-unified-local-model-minicpm5-hardware-acceleration-design.md)

## Global Constraints

- Android API level floor: 26 (Android 8.0 Oreo), target API 34/35.
- Zero mock delays or fake token generation: all streaming and parsing must handle live token flows.
- Pure Kotlin fallbacks if `liboorty_engine.so` is missing or on non-ARM host JVMs.
- Total memory ceiling for local model: $\le 1.2\text{ GB}$ DRAM to guarantee zero LMK terminations on 4GB devices.

---

### Task 1: Update Model Catalog with Unified MiniCPM5-1B Thinking Spec

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/pocket/PocketModels.kt`
- Test: `app/src/test/java/com/sednium/kryptonide/agent/pocket/PocketModelsTest.kt`

**Interfaces:**
- Consumes: `LocalModelSpec`, `ModelFormat` from `PocketModels.kt`
- Produces: `PocketModels.PREDEFINED_CATALOG` with `minicpm5-1b-thinking-q4` as the default primary model

- [ ] **Step 1: Write test verifying MiniCPM5-1B specification in catalog**
- [ ] **Step 2: Run test to confirm it fails (model not yet in catalog)**
- [ ] **Step 3: Update `PocketModels.kt` to include `minicpm5-1b-thinking-q4` with parameters, RAM requirements, download URLs, and set as primary**
- [ ] **Step 4: Run test to confirm it passes**

---

### Task 2: Build MiniCPM XML Tool & Thinking Parser (`MiniCpmToolParser`)

**Files:**
- Create: `app/src/main/java/com/sednium/kryptonide/agent/pocket/MiniCpmToolParser.kt`
- Test: `app/src/test/java/com/sednium/kryptonide/agent/pocket/MiniCpmToolParserTest.kt`

**Interfaces:**
- Consumes: Raw streaming text strings containing `<think>...</think>` and `<function name="...">...</function>`
- Produces: `ParsedModelOutput(val thinkContent: String?, val toolCalls: List<NeedleToolCall>, val cleanContent: String)`

- [ ] **Step 1: Write failing unit test covering XML function parsing, CDATA blocks, multi-parameter extraction, and `<think>` extraction**
- [ ] **Step 2: Run test to confirm failure**
- [ ] **Step 3: Implement `MiniCpmToolParser.kt` with robust regex and XML streaming token matching**
- [ ] **Step 4: Run test to confirm it passes**

---

### Task 3: Enhance OORTY Native Hardware Engine & JNI Bridge

**Files:**
- Modify: `app/src/main/cpp/oorty_engine.cpp`
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/hardware/OortyNativeBridge.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/hardware/HardwareTopology.kt`
- Test: `app/src/test/java/com/sednium/kryptonide/agent/hardware/HardwareTopologyTest.kt`

**Interfaces:**
- Consumes: Linux sysfs frequencies, GPU device nodes (`/dev/kgsl-3d0`, `/dev/mali0`)
- Produces: `nativeBindThreadToBigCores(threadIdx: Int)`, `nativeSetSocProfile(profile: Int)`, `nativeGetGpuVendor()`, `HardwareTopology.scanTopology()`

- [ ] **Step 1: Write unit test validating hardware topology fallbacks and core partitioning**
- [ ] **Step 2: Update `oorty_engine.cpp` with SoC profile handling (Dimensity Dual/Quad, Snapdragon Mid/Flagship) and GPU vendor detection**
- [ ] **Step 3: Update `OortyNativeBridge.kt` and `HardwareTopology.kt` with matching JNI methods and KV cache profile helpers**
- [ ] **Step 4: Run tests to verify clean compilation and fallback logic**

---

### Task 4: Upgrade `LocalGgufRunner` with MiniCPM5 Prompt Template & OORTY Pinning

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/pocket/LocalGgufRunner.kt`
- Test: `app/src/test/java/com/sednium/kryptonide/agent/pocket/LocalGgufRunnerTest.kt`

**Interfaces:**
- Consumes: `MiniCpmToolParser`, `HardwareTopology.pinThreadToBigCores`, `TOOLS_JSON` schema
- Produces: `executeStreamingInference(modelFile, prompt, systemPrompt, kvCacheQuant)` emitting live tokens and tool calls

- [ ] **Step 1: Write unit test verifying prompt formatting matches MiniCPM5 Jinja chat template with `<tools>` block**
- [ ] **Step 2: Implement prompt generator and streaming parser integration in `LocalGgufRunner.kt`**
- [ ] **Step 3: Add KV cache quantization option (`AUTO`, `Q8_0`, `Q4_0`) to runner parameters**
- [ ] **Step 4: Run unit tests to confirm formatting and parser integration pass**

---

### Task 5: Integrate Unified Model into `PocketServerManager` & UI

**Files:**
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/pocket/PocketServerManager.kt`
- Modify: `app/src/main/java/com/sednium/kryptonide/agent/ui/PocketServerScreen.kt`

**Interfaces:**
- Consumes: `PocketModels.PREDEFINED_CATALOG`, `MiniCPM5` model spec
- Produces: UI card displaying MiniCPM5-1B Thinking as the recommended default model with one-click download and launch

- [ ] **Step 1: Update `PocketServerManager` to default to `minicpm5-1b-thinking-q4` when no model is active**
- [ ] **Step 2: Update `PocketServerScreen.kt` to present MiniCPM5-1B Thinking as the featured local model badge**
- [ ] **Step 3: Verify clean build and compilation via Gradle**

---

### Task 6: Full Verification & Test Suite Execution

- [ ] **Step 1: Run all unit tests (`./gradlew testDebugUnitTest`)**
- [ ] **Step 2: Verify zero regressions across AI, Pocket, and Hardware subsystems**
