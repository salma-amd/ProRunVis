% =====================================================================
% PREAMBLE — add these lines to your main .tex preamble (before \begin{document})
% =====================================================================
%
% \usepackage{xcolor}
% \usepackage{listings}
%
% \definecolor{tracecolor}{RGB}{0, 80, 170}       % blue for tracing calls
% \definecolor{codebg}{RGB}{248, 248, 248}         % light grey background
% \definecolor{codeframe}{RGB}{200, 200, 200}      % frame border
% \definecolor{keywordcolor}{RGB}{0, 0, 140}       % Java keywords
% \definecolor{commentcolor}{RGB}{100, 100, 100}   % comments
%
% \lstdefinestyle{javastyle}{
%   language=Java,
%   basicstyle=\footnotesize\ttfamily,
%   keywordstyle=\color{keywordcolor}\bfseries,
%   commentstyle=\color{commentcolor}\itshape,
%   backgroundcolor=\color{codebg},
%   frame=single,
%   rulecolor=\color{codeframe},
%   framesep=6pt,
%   xleftmargin=8pt,
%   xrightmargin=4pt,
%   numbers=none,
%   tabsize=4,
%   breaklines=false,
%   showstringspaces=false,
%   columns=flexible,
%   aboveskip=6pt,
%   belowskip=6pt,
% }
%
% =====================================================================

# 2 Background and Related Work

This chapter introduces the foundational concepts and tools that underpin this thesis. Section~2.1 discusses dynamic program analysis and control-flow tracing as techniques for understanding program behavior. Section~2.2 describes abstract syntax trees and their role in program analysis tools. Section~2.3 presents ProRunVis, the visualization tool that this thesis extends. Section~2.4 introduces SrcTracer, the tracing tool whose output this thesis integrates. Section~2.5 surveys related work in execution trace visualization and trace format interoperability.

---

## 2.1 Dynamic Program Analysis and Control-Flow Tracing

Program analysis techniques fall into two broad categories: static analysis, which examines source code or compiled artifacts without executing the program, and dynamic analysis, which observes the program's behavior during execution \cite{cornelissen2009}. Static analysis can reason about all possible execution paths but may produce imprecise results for programs with complex runtime behavior, such as polymorphism, reflection, or data-dependent branching. Dynamic analysis, by contrast, observes the actual execution path taken for a specific input, providing precise but necessarily incomplete information about the program's behavior.

Control-flow tracing is a form of dynamic analysis that records the sequence of control-flow decisions made during a program's execution \cite{ball1994}. At each branch point, such as an if-statement, a loop condition, a switch case, or a method call, the tracing mechanism records which path was taken. The result is a trace: a sequence of events that, together with the program's source code, allows the complete execution path to be reconstructed after the fact.

The standard approach to control-flow tracing is source-level instrumentation: before the program is compiled and executed, additional statements are inserted at each control-flow point to record the decision taken at that point \cite{graetz2025}. The instrumented program is then compiled and run normally, producing a trace file alongside its regular output. This approach is transparent to the programmer, as the program behaves identically to the original except for the additional trace output, and requires no special runtime support beyond a simple output mechanism. Source-level instrumentation, as opposed to binary-level instrumentation (which modifies compiled machine code or bytecode), has the advantage that the trace can be mapped directly back to the original source code, since the instrumentation points correspond one-to-one with source-level control-flow constructs \cite{graetz2025}.

Figure~\ref{fig:instrumentation-example} illustrates this process with a concrete example. A Java method containing an if/else branch and a for-loop is shown in its original form (a), after source-level instrumentation with SrcTracer tracing calls (b), and with the resulting text trace produced when the method is called with `x = 3` (c). Each tracing call records one control-flow decision as a single character: `C1` for calling function 1, `O` for taking the else-branch, `I` for entering the loop body, and `R` for returning. Reading the trace alongside the source code reveals that the if-condition evaluated to false, the loop executed three iterations, and the method returned normally.

\begin{figure}[t!]

%% --- (a) Original source ---
\noindent\begin{minipage}[t]{0.47\textwidth}
\centering\small\textbf{(a) Original source}
\begin{lstlisting}[style=javastyle]
static int demo(int x) {
    if (x < 0) {
        return -1;
    }
    int sum = 0;
    for (int i = 0; i < x; i++) {
        sum += i;
    }
    return sum;
}
\end{lstlisting}
\end{minipage}%
\hfill
%% --- (b) Instrumented source ---
\begin{minipage}[t]{0.51\textwidth}
\centering\small\textbf{(b) Instrumented source}
\begin{lstlisting}[style=javastyle, escapeinside={(@}{@)}]
static int demo(int x) {
    (@\textcolor{tracecolor}{\textbf{Trace.\_FUNC(1);}}@)
    if (x < 0) {
        (@\textcolor{tracecolor}{\textbf{Trace.\_IF();}}@)
        (@\textcolor{tracecolor}{\textbf{Trace.\_RETURN();}}@)
        return -1;
    } else {
        (@\textcolor{tracecolor}{\textbf{Trace.\_ELSE();}}@)
    }
    int sum = 0;
    for (int i = 0; i < x; i++) {
        (@\textcolor{tracecolor}{\textbf{Trace.\_LOOP\_BODY();}}@)
        sum += i;
    }
    (@\textcolor{tracecolor}{\textbf{Trace.\_LOOP\_END();}}@)
    (@\textcolor{tracecolor}{\textbf{Trace.\_RETURN();}}@)
    return sum;
}
\end{lstlisting}
\end{minipage}

\vspace{0.8em}

%% --- (c) Trace output ---
\begin{center}
\small\textbf{(c) Text trace for \texttt{demo(3)}}

\vspace{0.4em}

\fbox{\texttt{\large C1OIIIORE}}

\vspace{0.6em}

\small
\renewcommand{\arraystretch}{1.15}
\begin{tabular}{cl}
\hline
\textbf{Token} & \textbf{Meaning} \\
\hline
\texttt{C1} & Call function with ID\,1 (\texttt{demo}) \\
\texttt{O}  & Else-branch: \texttt{x < 0} evaluated to false \\
\texttt{I}  & Loop body entered (iteration 1, \texttt{i\,=\,0}) \\
\texttt{I}  & Loop body entered (iteration 2, \texttt{i\,=\,1}) \\
\texttt{I}  & Loop body entered (iteration 3, \texttt{i\,=\,2}) \\
\texttt{O}  & Loop exit: \texttt{i < x} evaluated to false \\
\texttt{R}  & Return statement reached \\
\texttt{E}  & Program end \\
\hline
\end{tabular}
\end{center}

\caption{Source-level instrumentation and trace generation. The original Java method~(a) is transformed by inserting \textcolor{tracecolor}{\texttt{srctracer.Trace}} calls at every control-flow point~(b). When the instrumented program executes with \texttt{x\,=\,3}, these calls produce the compact text trace shown in~(c). The trace captures only the control-flow path, not variable values.}
\label{fig:instrumentation-example}
\end{figure}

The primary trade-off in control-flow tracing is between the completeness of the recorded information, the size of the trace, and the runtime overhead of recording it. Recording every variable assignment, heap state, or stack frame at each decision point would produce a complete execution record but would be prohibitively expensive in both time and space. Recording only the control-flow path, meaning which branch was taken rather than what value was compared, captures sufficient information to reconstruct the execution path while keeping the trace compact. As Gr\"atz and H\"ahnle \cite{graetz2025} observe, this design choice also provides a privacy advantage: because the trace contains no variable values, user inputs, or data-dependent information, it can be shared between developers or transmitted to analysis tools without exposing sensitive runtime data. Variable values, if needed, must be recovered through other means, such as symbolic execution or re-execution with the same inputs.

The overhead introduced by source-level instrumentation depends on the recording mechanism. Gr\"atz and H\"ahnle \cite{graetz2025} report a median runtime overhead of approximately 45\% for the C implementation of SrcTracer when using a ring buffer for trace recording, demonstrating that source-level control-flow tracing is practical for real-world programs. The Java implementation, evaluated in Chapter~5 of this thesis, shows higher overhead due to the absence of preprocessor macros and the additional cost of JVM method dispatch, but remains within acceptable bounds for development and testing workflows.

### 2.1.1 Trace Formats

The representation of control-flow events in a trace varies across tools and determines both the trace's compactness and its usability for downstream analysis.

**Block-ID traces.** Some tools assign a unique integer identifier to each code block (e.g., the body of an if-branch, a loop body, or a method body) and record the sequence of block IDs entered during execution. This format is straightforward to produce and to consume: the trace is a sequence of integers, and each integer maps directly to a known location in the source code. However, integer identifiers are verbose (a 32-bit block ID occupies 4 bytes per event), and the identifiers are meaningful only in the context of the specific instrumenter that assigned them.

**Token-based traces.** An alternative approach, used by SrcTracer \cite{graetz2025}, encodes control-flow decisions as short character tokens rather than full block IDs. For example, entering the if-branch is recorded as the character `I`, entering the else-branch as `O`, a method call as `C` followed by a hexadecimal function identifier, and a return as `R`, as shown in Figure~\ref{fig:instrumentation-example}(c). This format is more compact, often requiring only a single byte per event, but requires knowledge of the program's structure to interpret. A token `I` means "the condition was true" but does not, on its own, identify which if-statement it refers to. Interpreting such a trace requires walking the program's source code alongside the trace, which is the fundamental operation that the abstract retracing mechanism in this thesis performs.

**Binary traces.** Token-based traces can be further compressed by encoding tokens as bit-packed binary data instead of human-readable characters. SrcTracer's binary mode \cite{graetz2025} packs up to six branch decisions into a single byte using a sentinel-bit scheme, and uses variable-length encoding for function identifiers. This reduces trace sizes by a factor of 4 to 6 compared to text traces, as confirmed by the benchmarks in Chapter~5 of this thesis. The trade-off is the loss of human readability and the need for specialized decoding logic.

---

## 2.2 Abstract Syntax Trees

An abstract syntax tree (AST) is a tree representation of the syntactic structure of source code, where each node corresponds to a construct in the language, such as a class declaration, a method, a statement, an expression, or a keyword \cite{aho2006}. Unlike a parse tree, which preserves all syntactic details including parentheses and semicolons, an AST abstracts away syntactically irrelevant elements and retains only the hierarchical structure of the program. Figure~\ref{fig:ast-example} illustrates this with a short Java code snippet and its corresponding AST. Each node in the tree represents a language construct, and the parent-child relationships reflect the nesting structure of the code. The \texttt{IfStmt} node, for example, has two children: the condition expression and the then-branch, which itself contains a block with a single assignment statement.

% --- Requires \usepackage{tikz} in preamble ---
\begin{figure}[ht]
\centering

\begin{minipage}[t]{0.35\textwidth}
\centering\small\textbf{Java source}
\begin{lstlisting}[style=javastyle]
if (x > 0) {
    sum += x;
}
\end{lstlisting}
\end{minipage}%
\hfill
\begin{minipage}[t]{0.60\textwidth}
\centering\small\textbf{Abstract Syntax Tree}

\vspace{0.5em}

\begin{tikzpicture}[
  every node/.style={
    draw,
    rounded corners=2pt,
    font=\small\ttfamily,
    minimum height=1.4em,
    inner sep=3pt,
  },
  level 1/.style={sibling distance=4.5cm, level distance=1.4cm},
  level 2/.style={sibling distance=3cm, level distance=1.4cm},
  level 3/.style={sibling distance=2.5cm, level distance=1.4cm},
  edge from parent/.style={draw, -stealth},
  cfnode/.style={fill=tracecolor!15, draw=tracecolor},
]

\node[cfnode] {IfStmt}
  child { node {BinaryExpr: x > 0}
    edge from parent node[left, draw=none, font=\scriptsize\rmfamily] {condition}
  }
  child { node[cfnode] {BlockStmt}
    child { node {ExpressionStmt}
      child { node {AssignExpr: sum += x} }
    }
    edge from parent node[right, draw=none, font=\scriptsize\rmfamily] {thenBranch}
  };

\end{tikzpicture}
\end{minipage}

\caption{A Java if-statement and its abstract syntax tree. Control-flow nodes (\texttt{IfStmt}, \texttt{BlockStmt}) are highlighted in \textcolor{tracecolor}{blue}; these are the nodes where both ProRunVis and SrcTracer insert tracing calls during instrumentation.}
\label{fig:ast-example}
\end{figure}

ASTs are the standard intermediate representation used by compilers, code analyzers, and refactoring tools to reason about program structure. For the tools discussed in this thesis, the AST serves as the common ground between tracing and visualization. Both ProRunVis (Section~2.3) and SrcTracer (Section~2.4) parse the source code into an AST and traverse it to insert tracing calls at control-flow nodes such as if-statements, loops, and method declarations. Because both tools operate on the same tree structure, in the same depth-first traversal order, the AST provides the structural correspondence that makes it possible to translate between their different trace formats. The abstract retracing mechanism developed in this thesis relies on this correspondence: it walks the AST alongside a SrcTracer trace, consuming one token at each control-flow node to reconstruct the equivalent sequence of ProRunVis block IDs.

Both tools use the JavaParser library \cite{javaparser} to parse Java source files. JavaParser provides a typed AST model where each language construct has its own node class (e.g., `IfStmt`, `ForStmt`, `SwitchEntry`, `MethodDeclaration`), which allows both instrumentation and trace interpretation to dispatch on the node type at each step of the traversal.

---

## 2.3 ProRunVis

ProRunVis \cite{prorunvis} is a program run visualization tool for Java developed at TU Darmstadt. It takes a Java project as input and produces an interactive visualization of a single execution run, showing which control-flow paths were taken, how many times loops iterated, and which methods were called.

### 2.3.1 Architecture

ProRunVis is implemented as a web application with a Spring Boot backend and a React frontend. The backend receives the uploaded Java project files and runs them through a processing pipeline. The result is a JSON structure that the frontend renders as an interactive code viewer, where the developer can navigate through method calls, loop iterations, and branch decisions, with the corresponding source code highlighted at each step.

The processing pipeline consists of the following phases:

1. **Parsing and preprocessing.** The uploaded Java source files are parsed into ASTs using JavaParser. A preprocessing step normalizes the ASTs, for example by adding explicit braces to single-line if/else and loop bodies, so that every control-flow branch has a block that can be assigned a unique identifier.

2. **Instrumentation.** The `Instrumenter` traverses each AST and inserts calls to `prorunvis.Trace.next_elem(blockId)` at the entry point of every control-flow block. It also records the mapping from block IDs to AST nodes in a `HashMap<Integer, Node>`, referred to as the trace map.

3. **Compilation and execution.** The instrumented source code is compiled and executed. During execution, the `next_elem()` calls write the sequence of block IDs to a trace file.

4. **Trace processing.** The `TraceProcessor` reads the block-ID sequence and constructs a tree of `TraceNode` objects. Each `TraceNode` represents an executed code block and contains metadata such as the source code range, parent-child relationships reflecting the nesting of the execution, and iteration numbers for loop bodies. This tree is serialized to JSON and returned to the frontend.

### 2.3.2 Trace Map

The trace map (`HashMap<Integer, Node>`) is the central data structure that connects the trace to the source code. It is populated during instrumentation: each time the `Instrumenter` visits a control-flow node and inserts a `next_elem(blockId)` call, it also adds an entry mapping the block ID to the AST node. During trace processing, this map is used to look up the source code location, node type, and structural context for each block ID in the trace.

The trace map is shared between the original pipeline and the SrcTracer integration path. In both cases, the `Instrumenter` runs and populates the trace map. The difference is in how the sequence of block IDs is obtained: in the original pipeline, it comes from executing the instrumented code; in the SrcTracer path, it comes from the `AbstractRetracer`.

### 2.3.3 Limitations

ProRunVis's compile-and-run pipeline requires the program to be compilable and executable within ProRunVis's environment. Programs with external dependencies (databases, network services, proprietary libraries) that are not available on the ProRunVis host cannot be traced. The pipeline also produces block-ID traces that are specific to ProRunVis's instrumenter and are not interoperable with other tracing tools. Furthermore, the `TraceProcessor` exhibits superlinear time complexity for long traces, as discussed in the evaluation (Chapter~5), which limits the tool's applicability to programs with short execution paths.

---

## 2.4 SrcTracer

SrcTracer \cite{srctracer, graetz2025} is a source-level control-flow tracing tool developed at TU Darmstadt for C/C++. Unlike ProRunVis, which records block IDs that are specific to its own instrumenter, SrcTracer records compact character tokens that encode only the control-flow decisions made during execution \cite{graetz2025}. The tool uses source-level instrumentation: it transforms the original source code by inserting tracing calls at every control-flow point (branches, loops, method calls, return statements, try/catch blocks), then compiles and runs the instrumented program to produce a trace file.

### 2.4.1 Trace Formats

SrcTracer supports two output formats. In **text mode**, each control-flow decision is recorded as a single character appended to a `.trace.txt` file: `I` for a taken branch or loop iteration, `O` for a not-taken branch or loop exit, `C` followed by a hexadecimal identifier for a function call, `R` for a return, `T` for entering a try block, and `E` for program end. The trace `C1OIIIORE`, for example, encodes a function call, a skipped if-branch, three loop iterations, a loop exit, a return, and program termination, as illustrated in Figure~\ref{fig:instrumentation-example}(c).

In **binary mode**, the same decisions are encoded as bit-packed bytes instead of characters. Branch decisions are accumulated into single bytes (up to six per byte), and function identifiers use variable-length encoding. This achieves 4 to 6 times compression compared to text traces. The instrumented source code is identical for both modes; only the runtime library differs.

### 2.4.2 Trace Call Placement

The placement of tracing calls follows a consistent principle: each call is inserted at the earliest point where the decision is known, but before any statement that would transfer control out of the current scope. Branch-entry calls (`_IF`, `_ELSE`) are placed as the first statement inside the branch body, because the decision is only resolved once execution enters that branch. Exit calls (`_RETURN`, `_BREAK`) are placed immediately before the exit statement, since after it executes, control leaves and no further tracing is possible. This placement strategy is illustrated in Figure~\ref{fig:instrumentation-example}(b).

### 2.4.3 Scope and Limitations

The C/C++ implementation of SrcTracer reports a median runtime overhead of approximately 45\% when using a ring buffer for trace recording \cite{graetz2025}. A key property of the trace is that it captures only the control-flow path, not variable values, which provides privacy guarantees when sharing traces across developers or organizations \cite{graetz2025}.

Prior to this thesis, SrcTracer existed only for C/C++, with a Python-based instrumenter and C runtime libraries. No Java implementation was available. The design and implementation of a Java port of SrcTracer, as well as its integration into ProRunVis through an abstract retracing mechanism, are the main contributions of this thesis and are described in Chapters~3 and~4.

---

## 2.5 Related Work

### 2.5.1 Execution Trace Visualization

Several approaches exist for visualizing program execution traces. Cornelissen et al.\ \cite{cornelissen2009} survey the field, categorizing tools by their visualization paradigm. Sequence-diagram-based tools such as javavis \cite{oechsle2002} and JIVE \cite{gestwicki2005} show method calls between objects over time, but become unwieldy for long traces. Flame graphs \cite{gregg2016} visualize call hierarchies for performance profiling, but are optimized for identifying bottlenecks rather than step-by-step debugging. ProRunVis belongs to a third category, code-centric visualizations, which annotate the source code itself with execution information. This approach keeps the developer grounded in the code they need to modify, and ProRunVis extends it with interactive navigation through method calls, loop iterations, and branch decisions.

### 2.5.2 Abstract Retracing

Most trace-based tools use a proprietary trace format tightly coupled to their own instrumentation, meaning a trace can only be consumed by the tool that produced it. The integration in this thesis bridges this gap by enabling ProRunVis to consume traces produced by SrcTracer's instrumentation.

The concept of abstract retracing originates from the C implementation of SrcTracer \cite{srctracer, retroac}. A Python script (`abstract_retrace.py`) reads a SrcTracer trace alongside the C source code and reconstructs which control-flow blocks were executed. This was developed to enable integration with CBMC \cite{cbmc} for single-path bounded model checking \cite{cbmc_retrace}, where the model checker follows the recorded trace to reconstruct plausible variable values.

This thesis adapts abstract retracing from C to Java, reimplementing it within ProRunVis's Java-based architecture. The adaptation requires handling Java-specific control-flow constructs (labeled break/continue, enhanced for-each loops, multi-catch, try-with-resources, switch fall-through) and interoperating with ProRunVis's existing data structures. The design and implementation are described in Chapters~3 and~4.

---

# Additional Bibliography Entries for Chapter 2

```bibtex
@book{aho2006,
  author = {Alfred V. Aho and Monica S. Lam and Ravi Sethi and Jeffrey D. Ullman},
  title = {Compilers: Principles, Techniques, and Tools},
  edition = {2},
  publisher = {Pearson/Addison-Wesley},
  year = {2006}
}

@misc{springboot,
  author = {{VMware, Inc.}},
  title = {{Spring Boot}},
  url = {https://spring.io/projects/spring-boot},
  year = {2024},
  note = {Accessed: 2025-06-04}
}

@misc{react,
  author = {{Meta Platforms, Inc.}},
  title = {{React}: A JavaScript Library for Building User Interfaces},
  url = {https://react.dev},
  year = {2024},
  note = {Accessed: 2025-06-04}
}

@inproceedings{oechsle2002,
  author = {Rainer Oechsle and Thomas Schmitt},
  title = {{JAVAVIS}: Automatic Program Visualization with Object and Sequence Diagrams Using the Java Debug Interface ({JDI})},
  booktitle = {Software Visualization: International Seminar},
  series = {LNCS},
  volume = {2269},
  pages = {176--190},
  publisher = {Springer},
  year = {2002},
  doi = {10.1007/3-540-45875-1_14}
}

@inproceedings{gestwicki2005,
  author = {Paul Gestwicki and Bharat Jayaraman},
  title = {{JIVE}: Java Interactive Visualization Environment},
  booktitle = {Companion to the 20th Annual ACM SIGPLAN Conference on Object-Oriented Programming, Systems, Languages, and Applications (OOPSLA '05)},
  pages = {226--228},
  publisher = {ACM},
  year = {2005},
  doi = {10.1145/1094855.1094954}
}

@article{gregg2016,
  author = {Brendan Gregg},
  title = {The Flame Graph},
  journal = {Communications of the ACM},
  volume = {59},
  number = {6},
  pages = {48--57},
  year = {2016},
  doi = {10.1145/2909476}
}
```
