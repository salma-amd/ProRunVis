% =====================================================================
% Chapter 3: Methodology
% =====================================================================
%
% PLAN (saved for reference):
%
% 3.1 Overall Architecture (~2-3 pages)
%   - Problem statement: ProRunVis and SrcTracer are incompatible
%   - End-to-end pipeline figure (both paths in one diagram)
%   - Comparison table: original path vs SrcTracer path
%   - Dual-path architecture: original compile-and-run preserved,
%     SrcTracer path activated by .trace.txt or .trace file
%
% 3.2 Java SrcTracer Instrumenter (~5-6 pages)
%   - Why a Java port was needed
%   - JavaParser-based visitor, post-order traversal, function ID assignment
%   - Construct-by-construct instrumentation
%   - Preprocessing (braces, AST normalization)
%   - Code examples
%
% 3.3 Trace Runtimes (~3-4 pages)
%   - Text runtime: srctracer.Trace API, tokens, file output
%   - Binary runtime: sentinel-bit encoding, ring buffer
%   - Why both exist (debugging vs production)
%   - Size comparison table
%
% 3.4 Abstract Retracer (~6-8 pages)
%   - Core idea: lockstep AST + trace walk
%   - Data structures: Token model, ControlFlow enum, reverse block-ID map, funcIdToMethod
%   - Algorithm walkthrough per construct
%   - Binary trace tokenizer (tokenizeBinary)
%   - Trace validation (validateTraceConsumed)
%
% 3.5 ProRunVis Integration (~3-4 pages)
%   - Web interface integration (SingleRunProcessingService)
%   - Auto-detection of .trace.txt/.trace files
%   - CLI integration (-s flag)
%   - TraceProcessor constructor overload
%   - Error handling: backend to frontend error display
%   - What stays unchanged
%
% 3.6 Testing and Validation (~2-3 pages)
%   - Test programs and verification approach
%   - Comparing both paths on same program
%   - Edge cases
%   - Token-by-token walkthrough
%
% =====================================================================

% =====================================================================
% PREAMBLE ADDITIONS for Chapter 3 (beyond what Chapter 2 already requires)
% =====================================================================
%
% Needs the same packages as Chapter 2 (xcolor, listings, tikz).
% Additional TikZ libraries for the pipeline figure:
%
% \usetikzlibrary{arrows.meta, positioning, fit, backgrounds, calc}
%
% =====================================================================


\chapter{Methodology}
\label{ch:methodology}

This chapter describes the design and implementation of the integration between SrcTracer and ProRunVis. Section~\ref{sec:architecture} presents the overall architecture and the dual-path design that preserves backward compatibility. Section~\ref{sec:java-instrumenter} describes the Java SrcTracer instrumenter. Section~\ref{sec:trace-runtimes} covers the text and binary trace runtimes. Section~\ref{sec:abstract-retracer} details the abstract retracer that converts SrcTracer traces into ProRunVis block IDs. Section~\ref{sec:prorunvis-integration} explains the changes made to ProRunVis's backend and web interface. Finally, Section~\ref{sec:testing-validation} discusses testing and validation.


\section{Overall Architecture}
\label{sec:architecture}

ProRunVis and SrcTracer were developed independently and produce fundamentally different trace formats. ProRunVis instruments Java source code by inserting \texttt{prorunvis.Trace.next\_elem(blockId)} calls at every control-flow point, then compiles and executes the instrumented program to produce a trace of integer block IDs. SrcTracer, in contrast, records compact single-character tokens representing branch decisions (such as \texttt{I} for entering a branch and \texttt{O} for skipping it), resulting in a more space-efficient but structurally different trace. Before this thesis, no mechanism existed to translate between the two formats, and SrcTracer had no Java implementation at all.

The goal of this work is to enable ProRunVis to visualize program runs that were traced using SrcTracer's lightweight instrumentation, without modifying ProRunVis's existing trace processing, JSON export, or frontend visualization. To achieve this, the integration introduces two new components: a Java implementation of SrcTracer (instrumenter and trace runtimes) and an abstract retracer that converts SrcTracer traces into the block-ID sequences that ProRunVis already understands.

\subsection{Dual-Path Design}
\label{sec:dual-path}

A central design decision is the dual-path architecture illustrated in Figure~\ref{fig:pipeline}. Rather than replacing ProRunVis's original pipeline, the integration adds an alternative path that is activated when a SrcTracer trace file is present. The original compile-and-run path remains fully functional, ensuring backward compatibility.

Both paths share the same initial stages: the Java source files are parsed into abstract syntax trees using JavaParser, the ASTs are normalized by the preprocessor (e.g., adding braces to single-line blocks), and the instrumenter assigns block IDs to control-flow nodes. At this point, the paths diverge:

\begin{itemize}
    \item \textbf{Original path:} The instrumented source code is compiled and executed. The program run produces a trace file of integer block IDs, which is read directly by the \texttt{TraceProcessor}.
    \item \textbf{SrcTracer path:} The compile-and-run step is skipped entirely. Instead, a pre-existing SrcTracer trace file (produced by running the program with SrcTracer's own runtime) is read by the \texttt{AbstractRetracer}, which walks the trace alongside the AST to reconstruct the equivalent block-ID sequence. This sequence is then passed to the same \texttt{TraceProcessor}.
\end{itemize}

From the \texttt{TraceProcessor} onward, both paths are identical: the block IDs are assembled into a \texttt{TraceNode} tree, serialized to JSON, and rendered by the React frontend. This means that neither the trace processing logic nor the visualization needed any modification to support SrcTracer traces.

The path selection happens automatically. In the web interface, the \texttt{SingleRunProcessingService} scans the uploaded project directory for files ending in \texttt{.trace.txt} (text traces) or \texttt{.trace} (binary traces). If such a file is found, the SrcTracer path is used; otherwise, the original compile-and-run path executes as before. In the command-line interface, the user can also select the SrcTracer path explicitly using the \texttt{-s} flag.

% --- Pipeline Figure ---

\begin{figure}[ht]
\centering
\begin{tikzpicture}[
    node distance=0.6cm and 1.8cm,
    box/.style={draw, rounded corners, minimum width=2.8cm, minimum height=0.8cm,
                align=center, font=\small},
    shared/.style={box, fill=black!8},
    original/.style={box, fill=orange!15},
    srctracer/.style={box, fill=tracecolor!15},
    arr/.style={-{Stealth[length=5pt]}, thick},
    label/.style={font=\footnotesize\itshape, text=black!60}
]

% Shared top
\node[shared] (parse) {JavaParser};
\node[shared, below=of parse] (preprocess) {Preprocessor};
\node[shared, below=of preprocess] (instrument) {Instrumenter};

% Original path (left)
\node[original, below left=1.0cm and 1.5cm of instrument] (compile) {Compile \& Run};
\node[original, below=of compile] (blocktrace) {Block-ID Trace};

% SrcTracer path (right)
\node[srctracer, below right=1.0cm and 1.5cm of instrument] (srctrace) {SrcTracer Trace};
\node[srctracer, below=of srctrace] (retracer) {AbstractRetracer};

% Shared bottom
\node[shared, below=1.8cm of instrument] (processor) {TraceProcessor};
\node[shared, below=of processor] (json) {JSON Export};
\node[shared, below=of json] (frontend) {React Frontend};

% Arrows: shared top
\draw[arr] (parse) -- (preprocess);
\draw[arr] (preprocess) -- (instrument);

% Arrows: original path
\draw[arr] (instrument.south) -- ++(0,-0.3) -| (compile.north);
\draw[arr] (compile) -- (blocktrace);
\draw[arr] (blocktrace.south) -- ++(0,-0.3) -| (processor.west);

% Arrows: srctracer path
\draw[arr] (instrument.south) -- ++(0,-0.3) -| (srctrace.north);
\draw[arr] (srctrace) -- (retracer);
\draw[arr] (retracer.south) -- ++(0,-0.3) -| (processor.east);

% Arrows: shared bottom
\draw[arr] (processor) -- (json);
\draw[arr] (json) -- (frontend);

% Path labels
\node[label, left=0.1cm of compile] {Original path};
\node[label, right=0.1cm of srctrace] {SrcTracer path};

\end{tikzpicture}
\caption{Dual-path architecture. Shared stages (grey) are identical in both paths. The original path (orange) compiles and runs the instrumented code. The SrcTracer path (blue) reads a pre-existing trace and converts it via the \texttt{AbstractRetracer}. Both paths converge at the \texttt{TraceProcessor}.}
\label{fig:pipeline}
\end{figure}

% --- Comparison Table ---

Table~\ref{tab:path-comparison} summarizes the differences between the two paths.

\begin{table}[ht]
\centering
\caption{Comparison of the original ProRunVis path and the SrcTracer path.}
\label{tab:path-comparison}
\begin{tabular}{l|c|c}
\hline
\textbf{Stage} & \textbf{Original Path} & \textbf{SrcTracer Path} \\
\hline
Parse (JavaParser)     & shared & shared \\
Preprocess             & shared & shared \\
Instrument (block IDs) & shared & shared \\
Compile \& Run         & yes    & skipped \\
Trace source           & block-ID file & \texttt{.trace.txt} / \texttt{.trace} \\
Abstract retracing     & not needed & \texttt{AbstractRetracer} \\
TraceProcessor         & shared & shared \\
JSON export            & shared & shared \\
Frontend               & shared & shared \\
\hline
\end{tabular}
\end{table}
