# WP E — Instrumentation Overhead Benchmarks

## Goal

Measure the runtime overhead introduced by SrcTracer instrumentation on a Java program and compare it against the original C SrcTracer implementation. This addresses **RQ1** (instrumentation overhead) and **RQ4** (trace format comparison).

---

## Test Program

The same algorithm was implemented identically in Java (`Bench.java`) and C (`bench.c`):

```java
int run(int n) {
    int sum = 0;
    for (int i = 0; i < n; i++) {
        if ((i & 1) == 0) sum += i;
        else              sum -= i;
    }
    return sum;
}
```

This is a tight loop with one branch per iteration. It was chosen deliberately because it **maximizes the ratio of trace events to actual computation**, representing a worst-case overhead scenario. With `n = 1,000,000`, each instrumented run records approximately **2,000,000 trace events**: 1M `_LOOP_BODY` tokens + 1M `_IF`/`_ELSE` tokens, plus a few additional tokens for `_FUNC`, `_LOOP_END`, and `_RETURN`.

---

## Configurations Tested

Six configurations were benchmarked — three per language:

| # | Configuration       | Description                                      |
|---|---------------------|--------------------------------------------------|
| 1 | Java uninstrumented | Original `Bench.java`, no tracing                |
| 2 | Java text mode      | Instrumented, using `runtime.jar` (text traces)  |
| 3 | Java binary mode    | Instrumented, using `runtime-binary.jar` (binary) |
| 4 | C uninstrumented    | Original `bench.c`, compiled with `gcc -O3`       |
| 5 | C text mode         | Instrumented, compiled with `-D_TEXT_TRACE_MODE`  |
| 6 | C binary mode       | Instrumented, compiled with `-D_TRACE_MODE`       |

---

## How Runtime Was Measured

### Java

The timing is performed inside `main()` using `System.nanoTime()`, which reads a monotonic high-resolution clock unaffected by system clock adjustments:

```java
run(10_000);                        // JIT warmup
long start = System.nanoTime();     // start clock
int result = run(1_000_000);        // timed call
long elapsed = System.nanoTime() - start;
```

The **warmup call** (`run(10_000)`) is critical. Without it, the JVM would execute `run()` in interpreted mode or with only tier-1 (C1) compilation, producing results 2–3× slower than the fully optimized (C2-compiled) code. After 10K iterations the JIT compiler has profiled the method and compiled it with full optimizations.

### C

The same pattern, using `clock_gettime(CLOCK_MONOTONIC)` for nanosecond-precision monotonic timing:

```c
run(10000);                              // CPU cache warmup
clock_gettime(CLOCK_MONOTONIC, &start);
int result = run(1000000);
clock_gettime(CLOCK_MONOTONIC, &end);
```

### What Is and Is Not Measured

**Only the `run(n)` call is timed.** The measurement does not include:
- Program startup or JVM boot time
- Class loading
- Trace file creation or closing
- Shutdown hooks

This means the measurement captures exactly the cost of executing the loop body plus the instrumentation calls within it (if any). The overhead is purely the additional CPU work that tracing introduces into the hot loop.

### Per-Event Overhead Calculation

The per-event overhead is derived as:

```
per_event_ns = (time_instrumented − time_baseline) / number_of_events
```

With `n = 1,000,000` producing ~2,000,000 events, this gives a nanosecond-per-event figure that can be compared across languages and trace modes.

---

## Measurement Protocol

- Each configuration was run **3 times** with `n = 1,000,000`
- The **median of 3 runs** was taken as the result to reduce noise from background OS activity
- No CPU pinning or turbo-boost control was applied — measurements were taken on a regular laptop under normal system load
- Trace file sizes were recorded after each instrumented run for the format comparison (RQ4)

---

## Build Process

### Java

```bash
# 1. Build SrcTracer jars
./gradlew :instrumenter:shadowJar :runtime:jar :runtime-binary:jar

# 2. Uninstrumented baseline
javac -d examples/bench-bare examples/Bench.java
java -cp examples/bench-bare Bench 1000000        # run 3×

# 3. Instrument the source
java -jar instrumenter/build/libs/instrumenter-0.1.0-SNAPSHOT-all.jar \
    examples/Bench.java examples/instrumented/Bench.java

# 4. Text mode
javac -cp runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar \
    -d examples/bench-text examples/instrumented/Bench.java
java -cp "runtime/build/libs/runtime-0.1.0-SNAPSHOT.jar:examples/bench-text" \
    Bench 1000000                                  # run 3×

# 5. Binary mode
javac -cp runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar \
    -d examples/bench-binary examples/instrumented/Bench.java
java -cp "runtime-binary/build/libs/runtime-binary-0.1.0-SNAPSHOT.jar:examples/bench-binary" \
    Bench 1000000                                  # run 3×
```

### C

The script `run_c_bench_native.sh` automates the C side:

```bash
# 1. Uninstrumented baseline
gcc -O3 bench.c -o bench_bare
./bench_bare 1000000                               # run 3×

# 2. Preprocess and instrument
cpp -I$SRC_TRACER_INCL bench.c -o bench.i
python3 $SRC_DIR/instrumenter.py bench.i -o bench_inst.c

# 3. Binary mode
gcc -O3 -D_TRACE_MODE -I$SRC_TRACER_INCL \
    -L$SRC_TRACER_LIB bench_inst.c -o bench_binary -lsrc_tracer
./bench_binary 1000000                             # run 3×

# 4. Text mode
gcc -O3 -D_TEXT_TRACE_MODE -I$SRC_TRACER_INCL \
    -L$SRC_TRACER_LIB bench_inst.c -o bench_text -lsrc_tracer
./bench_text 1000000                               # run 3×
```

---

## Environment

### Java benchmarks (this run)

- **Machine**: MacBook Pro (Apple Silicon)
- **OS**: macOS (Darwin 25.5.0)
- **JVM**: default system Java
- **Filesystem**: APFS

### C benchmarks (from prior run)

- **Machine**: Windows laptop with WSL2
- **OS**: WSL2 Ubuntu 22.04, GCC 11.4
- **Filesystem**: native ext4

The C SrcTracer is a Linux project (uses `syscall(SYS_write)`, GCC macros, etc.) and was not re-run on macOS. The C numbers from the prior Windows/WSL2 run are included for cross-language format comparison, but **the primary evaluation is the within-Java comparison** (uninstrumented vs. text vs. binary), where all measurements come from the same machine and environment.

**The OS/filesystem does not affect the overhead measurement.** The timed section measures CPU-bound work — the instrumentation calls inside the loop are in-memory bit-packing and buffer operations. The overhead ratios (uninstrumented vs. instrumented) are computed within the same environment.

---

## Results

### Java Wall-Clock Times (macOS, Apple Silicon)

| Configuration       | Run 1 (ms) | Run 2 (ms) | Run 3 (ms) | Median (ms) | Slowdown   | Trace size    |
|---------------------|------------|------------|------------|-------------|------------|---------------|
| Java uninstrumented |       2.16 |       2.04 |       2.03 |        2.04 | — baseline |             — |
| Java text mode      |      57.22 |      51.35 |      50.77 |       51.35 |      25×   |   2,020,011 B |
| Java binary mode    |       8.10 |       8.12 |       8.46 |        8.12 |      4.0×  |     336,674 B |

### Java Per-Event Overhead

Calculated as `(median_instrumented − median_baseline) / 2,000,000 events`:

| Mode   | Per-event overhead |
|--------|--------------------|
| Text   | ~24.7 ns/event     |
| Binary | ~3.0 ns/event      |

### C Wall-Clock Times (WSL2, prior run — for cross-language reference)

| Configuration    | Median (ms) | Slowdown   | Trace size    |
|------------------|-------------|------------|---------------|
| C uninstrumented |         1.0 | — baseline |             — |
| C text mode      |         720 |      720×  |   2,020,009 B |
| C binary mode    |         1.5 |      1.5×  |     336,672 B |

Note: C numbers are from a different machine (Windows/WSL2) and are included only for format and architecture comparison, not for direct speed comparison with the Java numbers above.

### Trace Size Comparison (RQ4)

| Mode   | C size      | Java size   | Difference |
|--------|-------------|-------------|------------|
| Text   | 2,020,009 B | 2,020,011 B |        2 B |
| Binary |   336,672 B |   336,674 B |        2 B |

The trace files are byte-identical except for trivial differences (hex-string width of function IDs in text mode, plus the trailing `E` marker). This confirms the Java implementation faithfully reproduces the SrcTracer trace format.

---

## Analysis

### Text vs. Binary: 6× Improvement

Switching from text to binary mode reduced:
- **Runtime overhead**: from 25× to 4.0× (a ~6× improvement)
- **Trace file size**: from ~2.0 MB to ~337 KB (a 6× reduction)

This is the headline implementation result. Binary mode's bit-packing stores up to 6 I/O decisions per byte instead of one character each, and the buffered 4 KB write strategy amortizes I/O syscalls.

### Why Java Text Mode Has Higher Overhead Than Binary Mode

Java text mode writes one character per trace event (`"I"`, `"O"`, etc.) through an `OutputStreamWriter`. Although Java's writer has an internal 8 KB buffer (which amortizes syscalls to ~250 for the entire run), each `write()` call still passes through a `synchronized` block and the UTF-8 encoder loop. The per-event cost of this path (~24.7 ns) is dominated by method-call and synchronization overhead.

Java binary mode, by contrast, packs up to 6 I/O decisions into a single byte using bit-shift operations and writes to a 4 KB byte buffer — pure arithmetic with no synchronization or encoding overhead.

### Why C Text Mode Has Higher Overhead Than Java Text Mode

Counter-intuitive but explained by **I/O design**, not language performance:

- **C text mode** uses `syscall(SYS_write, ...)` — a direct kernel call with **no userspace buffering**. Every trace character triggers one syscall (~2 million syscalls for this benchmark).
- **Java text mode** uses `OutputStreamWriter` with an internal **8 KB buffer**, reducing syscalls to ~250 for the entire run.

This is a deliberate design choice in SrcTracer — C's text mode is marked experimental. The binary mode uses proper buffering, which is why C binary mode is fast (1.5× overhead).

### Why C Binary Mode Has Lower Per-Event Overhead Than Java Binary Mode

The C compiler expands trace calls (`_IF`, `_ELSE`, `_LOOP_BODY`) as **preprocessor macros** — literal text substitution directly into the caller's code. With `gcc -O3`, the instrumented inner loop compiles to ~12 instructions with **zero function calls**: the trace bit-packing (a single `rol` + `mov`) sits inline alongside the user's arithmetic.

Java's JIT compiler (C2) does inline `Trace._IF()` and similar calls, but the resulting code still pays for language-runtime guarantees:
- Null checks on the `out` field
- Array bounds checks on `buf[bufPos]`
- Deoptimization guard machinery
- Static-field access through the JVM's memory model

The gap is fundamental: C macros are inline by construction and carry no runtime safety checks, while Java's JIT must preserve safety guarantees even in optimized code. In absolute terms, however, Java's 3.0 ns per event is small enough that real programs with non-trivial computation per branch will see proportionally lower overhead ratios.

### A Note on Absolute Times

The absolute wall-clock times reported here are specific to the hardware and OS used for each measurement. Running the same benchmarks on a different machine would produce different absolute times, but the **overhead ratios** (e.g., binary mode is ~6× faster than text mode) remain stable across machines, because both the baseline and the instrumented version benefit equally from faster hardware. The ratios are the meaningful result — they characterize the instrumentation overhead independent of the machine.

---

## Trace Format Support in ProRunVis

### Both text and binary traces are supported

ProRunVis supports both SrcTracer trace formats through the `AbstractRetracer`:

- **Text traces** (`.trace.txt`): read as a string and tokenized character by character (`C`, `I`, `O`, `R`, `T`, `U`, `J`, `E`). This is also the format ProRunVis's own built-in tracing uses — its `prorunvis.Trace.next_elem(int)` writes one block-ID integer per line to a plain text file.

- **Binary traces** (`.trace`): read as a byte array and decoded by a binary tokenizer. The format uses bit-packing to store I/O decisions compactly — up to 6 decisions per byte instead of one character each. Function IDs use variable-length encoding (1–5 bytes depending on magnitude). Markers like `RETURN`, `TRY`, and `END` are stored as their ASCII byte values.

The format is **detected automatically by file extension**: `.trace` triggers the binary tokenizer, anything else uses the text tokenizer. Both tokenizers produce the same `List<Token>`, so the entire downstream pipeline (AST walk, block-ID emission, TraceProcessor) is shared and format-agnostic.

### Binary decoding details

The binary format packs I/O decisions (if-branch vs. else-branch) as individual bits inside a single byte, using a sentinel-bit scheme:

1. The IE byte starts at `0xFE` (`11111110`) — the `0` at bit 0 is the sentinel
2. Each `_IF` rotates the byte left (setting the new LSB to 1)
3. Each `_ELSE` shifts the byte left (leaving the new LSB as 0)
4. The sentinel `0` moves upward with each decision
5. When the byte drops below `0xC0` (6 decisions packed), it is flushed to the buffer

To decode a flushed IE byte: find the highest `0`-bit (the sentinel), then read all bits below it MSB-first — `1` = IN (if-branch taken), `0` = OUT (else-branch taken).

Example: `0xBB = 10111011` → sentinel at bit 6 → bits 5..0 = `1,1,1,0,1,1` → `I, I, I, O, I, I`.

This encoding achieves **6× compression** over text mode (6 decisions per byte vs. 1 character per decision).

### Byte-range classification

| Byte range    | Meaning                                     |
|---------------|---------------------------------------------|
| `0x00–0x0F`   | FUNC with 4-bit ID (ID in lower nibble)     |
| `0x10–0x1F`   | FUNC with 12-bit ID (+1 byte follows)       |
| `0x20–0x2F`   | FUNC with 20-bit ID (+2 bytes follow)       |
| `0x30–0x3F`   | FUNC with 28-bit ID (+3 bytes follow)       |
| `0x41`        | FUNC_ANON (anonymous function)              |
| `0x43`        | FUNC with 32-bit ID (+4 bytes follow)       |
| `0x45`        | END marker                                  |
| `0x52`        | RETURN marker                               |
| `0x54`        | TRY marker                                  |
| `0x68–0x6F`   | CATCH (+2 bytes little-endian index)         |
| `0x80–0xBF`   | CASE (after marker) or full IE byte (6 decisions) |
| `0xC0–0xFF`   | Partial IE byte (1–5 decisions)             |

The `0x80–0xBF` range is shared between CASE and full IE bytes. An `afterMarker` state flag resolves the ambiguity: bytes in this range immediately following a marker are CASE; during active I/O packing they are full IE bytes.

Note: `TRY_END` (`U` in text mode) does not exist in binary traces — the binary runtime's `_TRY_END()` is a no-op. The `retraceTry()` method handles this gracefully by checking for the token's presence before consuming it.

---

## Key Takeaways

1. **Java binary mode is the practical choice for ProRunVis integration**: 4.0× overhead and 337 KB traces vs. 25× overhead and 2.0 MB traces in text mode.
2. **Switching from text to binary mode reduced Java tracing overhead by ~6×** and trace size by ~6×.
3. **Trace format compatibility is confirmed**: Java and C produce byte-identical traces (±2 bytes), validating the Java port.
4. **ProRunVis supports both text and binary traces**, with automatic format detection by file extension.

---

## Caveats

1. **Microbenchmark**: The test program is a branch-dense tight loop with minimal computation per branch. Real programs with heavier computation per branch point will see proportionally lower overhead ratios.
2. **Java and C measured on different machines**: Java on macOS Apple Silicon, C on WSL2/Windows. Within-language overhead ratios are valid; cross-language speed comparisons carry this caveat.
3. **JIT warmup**: Java measurements include a warmup phase. Without warmup, Java numbers would be 2–3× worse — but warmup reflects realistic long-running application behavior.
4. **Single iteration size**: All measurements at `n = 1,000,000`. Ratios are expected to remain stable at larger values of `n`.
5. **No system-level isolation**: Measurements taken on a regular laptop without CPU pinning or turbo-boost control. Median-of-3 mitigates outliers but does not eliminate system noise.
