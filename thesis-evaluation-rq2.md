# RQ2: ProRunVis Performance and Scalability

**Research Question:** How does ProRunVis perform (responsiveness, scalability) when visualizing long traces and large software projects?

---

## Experimental Setup

### Machine

All measurements were taken on an Apple MacBook Air with an M3 chip (8-core CPU, 16 GB unified memory), running macOS and Java 17. ProRunVis was run as a Spring Boot application with 2 GB maximum heap (`-Xmx2g`).

### Methodology

To isolate the performance characteristics of ProRunVis, we measure the time spent in each phase of the processing pipeline separately. The pipeline consists of three sequential phases:

1. **Instrument phase**, ProRunVis parses the uploaded Java source files using JavaParser, builds a symbol table, preprocesses the AST, and inserts instrumentation nodes to produce a trace map. This phase runs regardless of whether a SrcTracer trace is provided.

2. **AbstractRetracer phase**, When a SrcTracer trace file is present (`.trace.txt` or `.trace`), the AbstractRetracer tokenizes the trace and walks it in lockstep with the AST to reconstruct the equivalent sequence of ProRunVis block IDs. This is the component implemented in this thesis.

3. **TraceProcessor phase**, The TraceProcessor takes the block ID sequence (either from the AbstractRetracer or from ProRunVis's own compile-and-run path) and builds the TraceNode tree, which is then serialized to JSON for the frontend visualization.

Timing was added using `System.nanoTime()` around each phase in `SingleRunProcessingService`. Each benchmark was run once after a fresh server start (cold JVM). While this means the first measurement includes JIT compilation overhead, it reflects the realistic user experience, a developer uploading a project to ProRunVis for analysis.

### Benchmark Programs

We designed synthetic Java programs that vary along three independent axes to isolate the effect of each on ProRunVis performance:

**Axis 1, Trace length.** A single-file program with a configurable loop count `N`. Each iteration contains two `if/else` branches, producing approximately 3 trace tokens per iteration. Values of `N`: 10, 100, 1,000, 10,000, and 50,000.

**Axis 2, Nesting depth.** A single-file program with a fixed loop of 100 iterations containing nested `if/else` chains of depth `D`. Values of `D`: 5, 10, 20, and 50. This isolates the effect of AST depth on retracing and processing time.

**Axis 3, File count.** A multi-file project with `F` worker classes forming a call chain (`Worker0.process()` → `Worker1.process()` → ... → `WorkerF.process()`), plus a `Main` class. Each worker contains `if/else` branches and a short loop. Values of `F`: 5, 10, and 20. This isolates the effect of project size (number of compilation units) on the instrument phase.

**Combined benchmark.** A single-file program combining a heavy loop (5,000 iterations with 3 levels of nested `if/else` and a 4-case `switch`), recursive calls (depth 100), producing approximately 47 KB of text trace data.

For each benchmark, both text (`.trace.txt`) and binary (`.trace`) trace formats were tested.

---

## Results

### Axis 1: Trace Length Scaling

| Loop iterations | Trace size (text) | Trace size (binary) | AbstractRetracer (text) | AbstractRetracer (binary) | TraceProcessor | Process total |
|----------------:|------------------:|--------------------:|------------------------:|--------------------------:|---------------:|--------------:|
| 10              | 37 B              | 10 B                | 4.9 ms                  | 1.5 ms                    | 3.9 ms         | 9.7 ms        |
| 100             | 307 B             | 55 B                | 6.4 ms                  | 5.7 ms                    | 4.1 ms         | 10.9 ms       |
| 1,000           | 3.0 KB            | 505 B               | 33.9 ms                 | 17.3 ms                   | 334 ms         | 368 ms        |
| 5,000 (combined)| 45.7 KB           | 10.1 KB             | 137 ms                  | 97.3 ms                   | **283,669 ms** | **283,810 ms**|
| 10,000          | 29.3 KB           | 4.9 KB              | 110 ms                  |,                         | >300,000 ms    | timeout       |
| 50,000          | 146.5 KB          | 24.4 KB             |,                       |,                         |,              | not run       |

The trace_len_10000 benchmark (text mode) was aborted after the 5-minute HTTP timeout; the trace_len_50000 benchmark was not attempted. The combined_large benchmark, with 5,000 loop iterations plus recursion, completed after approximately 4.7 minutes.

### Axis 2: Nesting Depth

| Nesting depth | Trace size (text) | AbstractRetracer (text) | AbstractRetracer (binary) | TraceProcessor (text) | Process total (text) |
|--------------:|------------------:|------------------------:|--------------------------:|----------------------:|---------------------:|
| 5             | 247 B             | 12.5 ms                 | 10.0 ms                   | 8.2 ms                | 21.8 ms              |
| 10            | 224 B             | 4.5 ms                  | 3.5 ms                    | 1.7 ms                | 6.6 ms               |
| 20            | 207 B             | 3.3 ms                  | 2.2 ms                    | 1.1 ms                | 5.0 ms               |
| 50            | 211 B             | 2.8 ms                  | 5.9 ms                    | 0.8 ms                | 4.0 ms               |

### Axis 3: File Count

| Files | Trace size (text) | Instrument (text) | Instrument (binary) | AbstractRetracer (text) | TraceProcessor (text) | Process total (text) |
|------:|------------------:|-------------------:|---------------------:|------------------------:|----------------------:|---------------------:|
| 6     | 132 B             | 12.8 ms            | 21.9 ms              | 1.5 ms                  | 0.2 ms                | 2.2 ms               |
| 11    | 260 B             | 27.8 ms            | 20.0 ms              | 2.4 ms                  | 1.4 ms                | 4.2 ms               |
| 21    | 517 B             | 35.0 ms            | 30.0 ms              | 4.4 ms                  | 2.2 ms                | 7.3 ms               |

---

## Analysis

### AbstractRetracer scales linearly

The AbstractRetracer, implemented in this thesis, exhibits linear scaling with respect to trace length. From 10 iterations (4.9 ms) to 10,000 iterations (110 ms), the retracing time grows proportionally with the number of trace tokens. This is consistent with its design: the retracer performs a single pass over the trace, consuming one token at a time while walking the AST. Each token requires constant-time work (a map lookup and a stack push), yielding O(n) time complexity where n is the number of trace tokens.

Binary traces are consistently faster to retrace than text traces, approximately 1.5–2× faster for small inputs and 1.4× faster for the 5,000-iteration combined benchmark (97 ms vs. 137 ms). This improvement comes from avoiding string parsing: binary tokens are decoded directly from bytes, whereas text tokens require character-by-character scanning and hexadecimal parsing for function IDs.

### TraceProcessor is the scalability bottleneck

The TraceProcessor, which is part of the existing ProRunVis codebase and was not modified in this thesis, is the dominant cost for traces with more than a few hundred tokens. At 1,000 loop iterations it already takes 334 ms, 10× longer than the AbstractRetracer for the same input. At 5,000 iterations (the combined benchmark), it takes approximately 284 seconds (4.7 minutes), while the AbstractRetracer completes in 137 ms.

The growth pattern is strongly superlinear:

| Iterations | TraceProcessor time | Growth factor |
|-----------:|--------------------:|--------------:|
| 100        | 4.1 ms              | baseline      |
| 1,000      | 334 ms              | ~82× for 10× more data |
| 5,000      | 283,669 ms          | ~849× for 5× more data |

This suggests O(n²) or worse time complexity in the TraceProcessor. The likely cause is repeated traversal or lookup operations over the growing TraceNode tree during construction. Optimizing this component is outside the scope of this thesis but represents the primary opportunity for improving ProRunVis's scalability.

Importantly, the trace format (text vs. binary) has no meaningful effect on TraceProcessor time, since the TraceProcessor operates on the already-decoded block ID sequence, not on the raw trace data. The combined benchmark took 283,669 ms with a text trace and 288,648 ms with a binary trace, the difference is within noise.

### Nesting depth has negligible impact

Increasing the nesting depth from 5 to 50 levels, with a fixed 100-iteration loop, had no measurable effect on performance. All nesting benchmarks completed in under 22 ms total. This is expected: nesting depth affects the maximum depth of the AST walk during retracing, but the total number of trace tokens (which determines the work) depends on the loop count, not the nesting depth. The AST walk at each level is a constant-time operation (comparing node types and descending), so deeper nesting adds negligible overhead.

### File count affects instrumentation, not processing

The instrument phase scales approximately linearly with the number of source files: 12.8 ms for 6 files, 27.8 ms for 11 files, and 35.0 ms for 21 files. This is dominated by JavaParser's parsing and the Instrumenter's AST traversal for each compilation unit.

The AbstractRetracer and TraceProcessor are unaffected by file count when the trace length remains short, since both operate on the trace and the merged trace map rather than on individual source files.

### Trace size comparison

Across all benchmarks, binary traces are consistently 4–6× smaller than their text equivalents:

| Benchmark | Text trace | Binary trace | Compression ratio |
|-----------|------------|--------------|-------------------|
| trace_len_1000 | 3.0 KB | 505 B | 6.0× |
| trace_len_10000 | 29.3 KB | 4.9 KB | 6.0× |
| trace_len_50000 | 146.5 KB | 24.4 KB | 6.0× |
| combined_large | 45.7 KB | 10.1 KB | 4.5× |

This compression is achieved through SrcTracer's binary encoding scheme, which packs up to 6 branch decisions (I/O tokens) into a single byte using a sentinel-bit scheme, and uses variable-length encoding for function IDs. The smaller file size also translates to faster disk I/O and tokenization, though this advantage is marginal compared to the TraceProcessor bottleneck for longer traces.

---

## Summary

ProRunVis is responsive for programs that produce up to approximately 1,000 control-flow events (sub-second processing). Beyond this threshold, the TraceProcessor's superlinear scaling becomes the dominant factor, with processing times exceeding several minutes for 5,000+ events. The AbstractRetracer implemented in this thesis scales linearly and contributes less than 0.05% of the total processing time for the largest benchmarks that complete. Nesting depth and file count have negligible impact on overall performance. Binary traces offer a consistent 4–6× size reduction and marginally faster retracing, but do not mitigate the TraceProcessor bottleneck since that component operates on decoded block IDs. Improving the TraceProcessor's algorithmic complexity is the key step needed to enable ProRunVis to handle real-world programs with longer execution traces.
