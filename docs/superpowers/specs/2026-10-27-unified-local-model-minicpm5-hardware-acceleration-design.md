# Unified Local Model & Hardware Acceleration Specification

**Date:** 2026-10-27  
**Status:** Approved  
**Author:** Antigravity & Sednium Technologies Architecture Team  
**Target Platform:** Android (ARM64) in KryptonIDE  

---

## 1. Executive Summary

KryptonIDE previously featured a fragmented local model catalog (Needle 3 for tool calling, Laya S1 router, Qwen 2.5 Coder 1.5B/7B, Gemma 2 2B, and MiniLM embedding). Based on system research from **Sednium-Technologies/OORTY** (`OORTY-ENG-RES-004`), running concurrent multi-model pipelines on mobile edge devices saturates unified DRAM memory bandwidth ($44 - 68\text{ GB/s}$) and triggers severe thermal throttling.

This specification consolidates on-device intelligence into **one unified model**:
- **Primary Model**: [**`GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking`**](https://huggingface.co/GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking)
- **Quantization**: GGUF `Q4_K_M` (~650 MB primary) and `Q8_0` (~1.1 GB secondary)
- **Core Capabilities**:
  1. Internal reasoning chain (`<think>...</think>`).
  2. Native XML tool calling (`<tools>`, `<function name="...">`).
  3. Claude Opus-distilled coding and conversational persona.
- **Hardware Acceleration**: Adopted from Sednium OORTY engine:
  1. JNI big-core threadpool affinity (`sched_setaffinity`).
  2. Vulkan / OpenCL FP16 vectorized compute shaders with zero-copy POSIX `mmap` UMA.
  3. Switchable KV Cache Quantization (`Q8_0` / `Q4_0`).
  4. Foreground Service Android LMK defense (`oom_score_adj <= 50`).

---

## 2. Hardware Acceleration Blueprint (OORTY Engine)

### 2.1 CPU Big-Core Pinning (`sched_setaffinity`)
Mobile ARM big.LITTLE / DynamIQ chipsets group cores into disparate frequency tiers (e.g., 4 efficiency cores @ 1.8GHz, 3 performance cores @ 2.8GHz, 1 prime core @ 3.2GHz).
- **Straggler Bottleneck Avoidance**: Spawning worker threads across all cores forces high-clocked cores to wait at synchronization barriers for efficiency cores.
- **Dynamic Topology Scan**: The native engine inspects `/sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq`. Any core running at $\ge 85\%$ of peak frequency is classified as a performance core.
- **Thread Binding**: Worker threads (typically 2 to 4) are pinned via `sched_setaffinity(0, &cpuset)`.

### 2.2 GPU Acceleration & Zero-Copy Memory
- **GPU Node Probing**: Probes `/dev/kgsl-3d0` (Qualcomm Adreno) and `/dev/mali0` (ARM Mali).
- **Vulkan / OpenCL Compute**: Enables FP16 vectorized arithmetic with `VK_KHR_shader_float16_int8`.
- **Zero-Copy POSIX `mmap`**: Maps `.gguf` weights directly into unified virtual address space, eliminating Java heap overhead and redundant host-to-device memory copies.

### 2.3 Switchable KV Cache Quantization (`Q8_0` / `Q4_0`)
- **`Q8_0` (Default)**: 50% memory bandwidth reduction with $<0.1\%$ perplexity change.
- **`Q4_0` (Extended Context)**: 75% memory bandwidth reduction, enabling 4k–8k context windows on 4GB–6GB RAM mobile devices without tripping the Android Low Memory Killer.

### 2.4 Android LMK Defense
Inference runs inside an active Foreground Service notification, keeping `oom_score_adj <= 50` so Android does not terminate the model runner while switching apps or compiling code.

---

## 3. Unified Model Prompt & Tool Calling Format

MiniCPM5-1B uses the ChatML format with an integrated XML tool-calling grammar defined in its Jinja template.

### 3.1 Prompt Structure
```
<|im_start|>system
You are Krypton Assistant, an expert AI software engineer with Claude Opus reasoning.
# Tools
You are provided with function signatures within <tools></tools> XML tags:
<tools>
{"type":"function","function":{"name":"read_file","description":"Read file contents","parameters":{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}}}
{"type":"function","function":{"name":"edit_lines","description":"Replace line range","parameters":{"type":"object","properties":{"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"},"new_content":{"type":"string"}},"required":["path","start_line","end_line","new_content"]}}}
</tools>
<|im_end|>
<|im_start|>user
Inspect build.gradle.kts and add the serialization plugin.
<|im_end|>
<|im_start|>assistant
<think>
User wants to modify build.gradle.kts. First I must read the current contents using read_file to inspect existing plugins.
</think>
<function name="read_file">
<param name="path">build.gradle.kts</param>
</function>
<|im_end|>
```

### 3.2 Tool Output Injection
```
<|im_start|>user
<tool_response>
plugins {
    alias(libs.plugins.android.application)
}
</tool_response>
<|im_end|>
<|im_start|>assistant
<think>
Now that I have read the file, I can insert the kotlin serialization plugin.
</think>
...
```

---

## 4. Architectural Modifications in KryptonIDE

1. **`PocketModels.kt`**:
   - Add `minicpm5-1b-thinking-q4` (ID: `minicpm5-1b-thinking-q4`) as the primary recommended local model.
   - Update download URL pointing to HuggingFace GGUF release (`GnLOLot/MiniCPM5-1B-Claude-Opus-Fable5-V2-Thinking`).
   - Deprecate multi-model sprawl by designating MiniCPM5-1B as the unified System 1 + Tool + Code model.

2. **`MiniCpmToolParser.kt`**:
   - Introduce dedicated XML tool parser to extract `<function name="...">...<param name="...">...</param></function>` blocks and map them directly to `AiChunk.ToolCallComplete` and `NeedleToolCall`.

3. **`LocalGgufRunner.kt`**:
   - Update prompt formatting to supply MiniCPM5's native `<tools>` schema and parse `<think>` tokens.
   - Wire OORTY hardware thread affinity and KV cache quantization parameters into execution.

4. **`OortyNativeBridge.kt` & `oorty_engine.cpp`**:
   - Ensure JNI functions for thread pinning (`sched_setaffinity`), GPU vendor detection, and hardware summary are complete and wired.

---

## 5. Testing & Verification Plan

- **`MiniCpmToolParserTest.kt`**: Verify parsing of single and multiple `<function>` calls with plain text and CDATA parameters.
- **`PocketModelsTest.kt`**: Verify model spec properties (parameters, RAM requirements, formats, download targets).
- **`HardwareTopologyTest.kt`**: Verify CPU core partitioning and GPU vendor detection fallback mechanisms.
