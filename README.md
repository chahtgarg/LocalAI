# Ordo Take-Home Assignment
## Running an LLM on a Smartphone

### 1. Setup and Environment

**Device:** Samsung Galaxy S23  
**RAM:** 8 GB  
**Inference threads:** 4  
**Generation length:** 64 tokens per run

The objective was to evaluate the feasibility of running small quantized language models locally on an Android device and understand their performance under mobile hardware constraints.

I focused on comparing two small instruction-following models and measuring their inference performance using prefill throughput, decode throughput, time to first token (TTFT), model loading time, and memory consumption.

The models explored were from the Qwen and Llama families. The Qwen model used in the earlier model experiments was Qwen2.5-1.5B-Instruct with Q4_K_M quantization. The Llama 3.2 1B Instruct model was also considered.

**Implementation note:** The exact Android model checkpoint filenames, quantization variants, and runtime configuration should be confirmed against the final Android project before submission.

The benchmark workload used a fixed generation length of 64 tokens. The measurements below represent preliminary observations rather than a fully controlled benchmarking study.

### 2. Results

| Model | Quantization | Runtime | Prefill (tok/s) | Decode (tok/s) | TTFT | Peak RAM | Load time |
|---|---|---|---:|---:|---:|---:|---:|
| Qwen | Q4_K_M* | Verify Android runtime | 93.92 | 25.89 | 490.32 ms | Not separately recorded | 7.90 s |
| Llama | Verify exact variant | Verify Android runtime | 129.38 | 36.45 | 363.83 ms | Not separately recorded | 5.23 s |

*Q4_K_M is confirmed for the Qwen laptop experiment; its Android checkpoint must be verified before assigning this quantization to the Android benchmark row.*

**Additional memory observation:**

- Peak process memory (VmHWM): approximately 2164 MB.
- This is process-level resident memory, not model-only memory.

**Measurement notes:**

- Qwen figures represent the median of three runs.
- Llama figures represent one initial run.
- Both were tested using four inference threads and a 64-token generation workload.
- The difference in the number of runs means the comparison is preliminary. More repeated measurements are needed for a statistically fair comparison.

### 3. What Surprised Me

The most interesting observation was the difference between prefill and decode throughput.

Prefill throughput was considerably higher than decode throughput for both models. Initially, it might seem that generating a token should be simpler than processing a long prompt. However, the two operations behave differently internally.

During prefill, the model processes the input prompt in parallel, allowing the hardware to make efficient use of available computation.

During decode, tokens are generated sequentially. Each new token depends on the previous context, and the model repeatedly accesses its weights. This makes generation more sensitive to memory bandwidth and sequential execution.

Another interesting observation was the difference in initial model loading time. In the preliminary results, Llama loaded in 5.23 seconds, compared with 7.90 seconds for Qwen.

I also observed approximately 1% battery reduction during the recorded sustained-generation workload. This highlighted that inference performance cannot be evaluated only through speed. Power consumption and thermal behaviour are equally important when the target environment is a smartphone.

The reported peak process memory of approximately 2164 MB also reinforced that the model file size alone does not represent the complete memory requirement. Runtime buffers, application memory, and inference-related allocations contribute to overall memory consumption.

### 4. Explanation of the Results

**Prefill versus decode**

Prefill processes multiple input tokens together, making parallel computation possible. Decode processes one new token at a time, making it sequential and often memory-bandwidth-bound.

This explains why decode throughput was lower despite each generation step involving less input processing.

**Differences between the models**

In the preliminary comparison, Llama showed higher prefill and decode throughput, lower TTFT, and shorter model loading time.

However, performance can depend on several factors, including model architecture, parameter count, quantization, runtime implementation, memory access patterns, and hardware optimisation.

Since the number of benchmark repetitions differed, I would not attribute the performance difference to a single factor without further testing.

**Quantization**

Quantization reduces the precision used to represent model weights.

Q4 uses approximately four bits per weight, while Q8 uses approximately eight bits. Lower-bit quantization generally reduces model storage requirements and memory bandwidth demands.

However, reducing the model size does not necessarily reduce latency proportionally. Computation, dequantization overhead, memory bandwidth, and runtime optimisation also affect performance.

**Memory consumption**

The measured VmHWM of approximately 2164 MB represents the peak resident memory of the application process.

It should not be interpreted as the exact memory occupied by the model because the application and inference runtime also consume memory.

**Mobile hardware considerations**

Unlike desktop systems, smartphones operate under stricter thermal and power constraints. Sustained inference can cause heating and thermal throttling, potentially reducing throughput.

The operating system may also reclaim memory or terminate applications when resources become limited. Therefore, reliable on-device inference requires appropriate memory management and lifecycle handling.

### 5. What I Couldn't Get Working / Limitations

The main limitation of the current benchmarking exercise was the lack of equally repeated measurements for both models.

Qwen results were collected across three runs, whereas the available Llama summary was based on one initial run. A larger number of repetitions under identical conditions would improve the reliability of the comparison.

The following areas also require further verification or more detailed measurement:

- A controlled comparison of the same model at Q4 and Q8 quantization.
- Exact Android checkpoint filenames and quantization configurations.
- A confirmed 500+ token prompt for prefill benchmarking.
- Separate model-only RAM measurement in addition to process-level memory.
- Detailed numerical results for sustained load, Battery Saver, memory pressure, backgrounding, and hot-versus-cold device conditions.
- Confirmed CPU, GPU, or NPU utilisation during inference.

I would also distinguish between a model successfully running on a device and a model being sufficiently optimised for production use. These are two different milestones.

### 6. What I Would Try Next With Another Week

Given another week, I would focus on improving the reliability of the experiments and making the implementation more production-oriented.

**1. Improve benchmarking**

- Repeat each benchmark multiple times under identical conditions.
- Report median throughput and variation.
- Use a consistent prompt containing at least 500 tokens.
- Compare Q4 and Q8 versions of the same model.
- Record power consumption and device temperature more systematically.

**2. Investigate hardware acceleration**

I would investigate whether the inference runtime can use the device's available GPU or NPU.

This would involve checking backend compatibility, supported model operations, and actual hardware utilisation rather than assuming that an available accelerator is being used.

**3. Improve memory management**

I would implement lazy model loading, release unused model resources, and handle interruptions caused by Android memory pressure.

The model should load when the user enters the conversational experience, remain available during active interaction, and unload when it is no longer needed or system memory becomes constrained.

**4. Improve the user experience**

For a production application, I would add model download progress, storage checks, interrupted-download recovery, loading indicators, and clear error handling.

Inference should run on background threads so that the main UI remains responsive.

**5. Explore AccessibilityService reliability**

I would test how consistently accessibility nodes can be identified across different Android versions and interface states.

I would also investigate failure handling, user confirmation for sensitive actions, and privacy safeguards.

### 7. Conclusion

This exercise demonstrated that running a language model locally is only one part of building a practical mobile AI assistant.

The larger challenge is achieving responsive inference while managing limited memory, battery consumption, thermal constraints, and Android's application lifecycle.

It also highlighted the distinction between understanding a user's instruction and reliably executing an action inside another application.

For a product such as Ordo, both capabilities need to work together: the language model must interpret the request efficiently, while the Android application must execute the intended action safely and reliably.
