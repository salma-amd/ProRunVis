package prorunvis.trace.process;

import com.github.javaparser.Range;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.ModifierVisitor;
import com.github.javaparser.ast.visitor.Visitable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Converts a SrcTracer text trace into the sequence of ProRunVis block IDs
 * by walking the trace and the AST in lockstep (abstract retracing).
 *
 * The output is a {@link Stack} of block IDs that can be fed directly
 * into {@link TraceProcessor}.
 */
public class AbstractRetracer {

    // ── Trace token model ──────────────────────────────────────────────

    private enum TokenType {
        FUNC, IN, OUT, RETURN, TRY, TRY_END, CATCH, END
    }

    private record Token(TokenType type, int value) {}

    private enum ControlFlow {
        NORMAL, RETURN, BREAK, CONTINUE
    }

    // ── Fields ──────────────────────────────────────────────────────────

    private final List<CompilationUnit> cus;
    private final Map<Integer, Node> blockIdMap;
    private final Path rootDir;

    private final Map<Integer, MethodDeclaration> funcIdToMethod;
    private final Map<String, Integer> reverseBlockIdMap;

    private final List<Token> tokens;
    private int pos;
    private final List<Integer> output;

    // ── Constructor ─────────────────────────────────────────────────────

    public AbstractRetracer(final List<CompilationUnit> cus,
                            final Map<Integer, Node> blockIdMap,
                            final String traceFilePath,
                            final Path rootDir) throws IOException {
        this.cus = cus;
        this.blockIdMap = blockIdMap;
        this.rootDir = rootDir.toAbsolutePath();
        this.output = new ArrayList<>();

        this.funcIdToMethod = buildFuncIdMap();
        this.reverseBlockIdMap = buildReverseBlockIdMap();
        this.tokens = tokenize(Files.readString(Path.of(traceFilePath)).trim());
        this.pos = 0;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Run the abstract retracing and return the block-ID sequence
     * as a stack (top = first ID), ready for {@link TraceProcessor}.
     */
    public Stack<Integer> retrace() {
        if (tokens.isEmpty()) {
            return new Stack<>();
        }

        Token first = consume(TokenType.FUNC);
        MethodDeclaration mainMethod = funcIdToMethod.get(first.value());
        if (mainMethod == null) {
            throw new IllegalStateException(
                    "No method found for funcId " + first.value());
        }
        emitBlockId(mainMethod);
        retraceMethodBody(mainMethod);

        Stack<Integer> stack = new Stack<>();
        List<Integer> reversed = new ArrayList<>(output);
        Collections.reverse(reversed);
        reversed.forEach(stack::push);
        return stack;
    }

    // ── AST walking ─────────────────────────────────────────────────────

    private ControlFlow retraceMethodBody(final MethodDeclaration method) {
        if (method.getBody().isEmpty()) {
            return ControlFlow.NORMAL;
        }
        return retraceBlock(method.getBody().get());
    }

    private ControlFlow retraceBlock(final BlockStmt block) {
        for (Statement stmt : block.getStatements()) {
            if (isProRunVisTraceCall(stmt)) {
                continue;
            }
            if (!hasMore()) {
                return ControlFlow.NORMAL;
            }
            if (peek().type() == TokenType.RETURN) {
                consume(TokenType.RETURN);
                return ControlFlow.RETURN;
            }
            if (peek().type() == TokenType.END) {
                return ControlFlow.RETURN;
            }

            ControlFlow flow = retraceStatement(stmt);
            if (flow != ControlFlow.NORMAL) {
                return flow;
            }
        }
        return ControlFlow.NORMAL;
    }

    private ControlFlow retraceStatement(final Statement stmt) {
        // handle method calls that may precede the statement's own trace events
        consumeMethodCalls(stmt);

        if (stmt instanceof IfStmt ifStmt) {
            return retraceIf(ifStmt);
        } else if (stmt instanceof ForStmt forStmt) {
            return retraceLoop(forStmt, forStmt.getBody().asBlockStmt());
        } else if (stmt instanceof WhileStmt whileStmt) {
            return retraceLoop(whileStmt, whileStmt.getBody().asBlockStmt());
        } else if (stmt instanceof DoStmt doStmt) {
            return retraceLoop(doStmt, doStmt.getBody().asBlockStmt());
        } else if (stmt instanceof SwitchStmt switchStmt) {
            return retraceSwitch(switchStmt);
        } else if (stmt instanceof TryStmt tryStmt) {
            return retraceTry(tryStmt);
        } else if (stmt instanceof ReturnStmt) {
            if (hasMore() && peek().type() == TokenType.RETURN) {
                consume(TokenType.RETURN);
            }
            return ControlFlow.RETURN;
        } else if (stmt instanceof BreakStmt) {
            return ControlFlow.BREAK;
        } else if (stmt instanceof ContinueStmt) {
            return ControlFlow.CONTINUE;
        } else if (stmt instanceof ThrowStmt) {
            return ControlFlow.RETURN;
        }
        return ControlFlow.NORMAL;
    }

    // ── If / else ───────────────────────────────────────────────────────

    private ControlFlow retraceIf(final IfStmt ifStmt) {
        if (!hasMore()) {
            return ControlFlow.NORMAL;
        }

        Token token = consume();
        if (token.type() == TokenType.IN) {
            // then-branch taken
            emitBlockId(ifStmt.getThenStmt());
            return retraceBlock(ifStmt.getThenStmt().asBlockStmt());
        } else {
            // else-branch (token is OUT)
            if (ifStmt.getElseStmt().isPresent()) {
                Statement elseStmt = ifStmt.getElseStmt().get();
                if (elseStmt.isIfStmt()) {
                    return retraceIf(elseStmt.asIfStmt());
                } else {
                    emitBlockId(elseStmt);
                    return retraceBlock(elseStmt.asBlockStmt());
                }
            }
            // no else in source → O just means skipped
            return ControlFlow.NORMAL;
        }
    }

    // ── Loops ───────────────────────────────────────────────────────────

    private ControlFlow retraceLoop(final Statement loopStmt,
                                    final BlockStmt body) {
        while (hasMore() && peek().type() == TokenType.IN) {
            consume(); // consume I (loop body entered)
            emitBlockId(loopStmt);

            ControlFlow flow = retraceBlock(body);
            if (flow == ControlFlow.BREAK) {
                // break suppresses the loop-end O
                return ControlFlow.NORMAL;
            }
            if (flow == ControlFlow.RETURN) {
                return ControlFlow.RETURN;
            }
            // CONTINUE: iteration ends, check for next I or O
        }

        // consume O (loop ended normally)
        if (hasMore() && peek().type() == TokenType.OUT) {
            consume();
        }
        return ControlFlow.NORMAL;
    }

    // ── Switch ──────────────────────────────────────────────────────────

    private ControlFlow retraceSwitch(final SwitchStmt switchStmt) {
        // src-tracer encodes case ID as 6 I/O bits (MSB first)
        int caseId = 0;
        for (int i = 5; i >= 0; i--) {
            if (!hasMore()) {
                break;
            }
            Token bit = consume();
            if (bit.type() == TokenType.IN) {
                caseId |= (1 << i);
            }
        }

        List<SwitchEntry> entries = switchStmt.getEntries();
        if (caseId < entries.size()) {
            SwitchEntry entry = entries.get(caseId);
            emitBlockId(entry);

            // walk the matched case (and fall-through cases)
            for (int i = caseId; i < entries.size(); i++) {
                SwitchEntry current = entries.get(i);
                if (i > caseId) {
                    emitBlockId(current);
                }
                for (Statement stmt : current.getStatements()) {
                    if (isProRunVisTraceCall(stmt)) {
                        continue;
                    }
                    ControlFlow flow = retraceStatement(stmt);
                    if (flow == ControlFlow.BREAK) {
                        return ControlFlow.NORMAL;
                    }
                    if (flow == ControlFlow.RETURN) {
                        return ControlFlow.RETURN;
                    }
                }
            }
        }
        return ControlFlow.NORMAL;
    }

    // ── Try / catch / finally ───────────────────────────────────────────

    private ControlFlow retraceTry(final TryStmt tryStmt) {
        if (!hasMore() || peek().type() != TokenType.TRY) {
            return ControlFlow.NORMAL;
        }
        consume(TokenType.TRY);
        emitBlockId(tryStmt);

        ControlFlow flow = retraceBlock(tryStmt.getTryBlock());

        if (hasMore() && peek().type() == TokenType.TRY_END) {
            // try block completed normally
            consume(TokenType.TRY_END);
        } else if (hasMore() && peek().type() == TokenType.CATCH) {
            // exception caught
            Token catchToken = consume(TokenType.CATCH);
            int catchIdx = catchToken.value();
            if (catchIdx < tryStmt.getCatchClauses().size()) {
                CatchClause clause = tryStmt.getCatchClauses().get(catchIdx);
                emitBlockId(clause);
                flow = retraceBlock(clause.getBody());
            }
        }

        // handle finally if present
        if (tryStmt.getFinallyBlock().isPresent()) {
            emitBlockId(tryStmt.getFinallyBlock().get());
            ControlFlow finallyFlow = retraceBlock(tryStmt.getFinallyBlock().get());
            if (finallyFlow != ControlFlow.NORMAL) {
                return finallyFlow;
            }
        }

        return flow;
    }

    // ── Method calls ────────────────────────────────────────────────────

    private void consumeMethodCalls(final Statement stmt) {
        // greedily consume FUNC tokens that appear before the statement's
        // own trace events — these are method calls within the statement
        while (hasMore() && peek().type() == TokenType.FUNC) {
            Token funcToken = consume(TokenType.FUNC);
            MethodDeclaration calledMethod = funcIdToMethod.get(funcToken.value());
            if (calledMethod != null) {
                emitBlockId(calledMethod);
                retraceMethodBody(calledMethod);
            }
        }
    }

    // ── Block-ID emission ───────────────────────────────────────────────

    private void emitBlockId(final Node node) {
        String key = makeNodeKey(node);
        Integer blockId = reverseBlockIdMap.get(key);
        if (blockId != null) {
            output.add(blockId);
        }
    }

    // ── Build funcId → MethodDeclaration map ────────────────────────────

    /**
     * Walks the ASTs in the same order as src-tracer's InstrumenterVisitor
     * to assign funcIds consistently.
     * src-tracer uses post-order (super.visit first, then assign ID).
     */
    private Map<Integer, MethodDeclaration> buildFuncIdMap() {
        Map<Integer, MethodDeclaration> map = new LinkedHashMap<>();
        int[] nextId = {1};

        for (CompilationUnit cu : cus) {
            cu.accept(new ModifierVisitor<Void>() {
                @Override
                public Visitable visit(final MethodDeclaration md, final Void arg) {
                    super.visit(md, arg);
                    if (md.getBody().isPresent()) {
                        map.put(nextId[0]++, md);
                    }
                    return md;
                }

                @Override
                public Visitable visit(final ConstructorDeclaration cd, final Void arg) {
                    super.visit(cd, arg);
                    // src-tracer assigns funcIds to constructors too,
                    // but ProRunVis doesn't instrument them — skip mapping
                    nextId[0]++;
                    return cd;
                }
            }, null);
        }
        return map;
    }

    // ── Build reverse block-ID map ──────────────────────────────────────

    private Map<String, Integer> buildReverseBlockIdMap() {
        Map<String, Integer> reverse = new HashMap<>();
        for (Map.Entry<Integer, Node> entry : blockIdMap.entrySet()) {
            Node node = entry.getValue();
            String key = makeNodeKey(node);
            reverse.put(key, entry.getKey());
        }
        return reverse;
    }

    private static String makeNodeKey(final Node node) {
        if (node.getRange().isEmpty()) {
            return "no-range-" + System.identityHashCode(node);
        }
        Range range = node.getRange().get();
        String fileName = node.findCompilationUnit()
                .flatMap(CompilationUnit::getStorage)
                .map(s -> s.getFileName())
                .orElse("unknown");
        return fileName + ":"
                + range.begin.line + ":" + range.begin.column + "-"
                + range.end.line + ":" + range.end.column;
    }

    // ── Helpers: detect ProRunVis trace calls ───────────────────────────

    private static boolean isProRunVisTraceCall(final Statement stmt) {
        if (!(stmt instanceof ExpressionStmt exprStmt)) {
            return false;
        }
        String code = exprStmt.toString();
        return code.contains("prorunvis.Trace.next_elem(");
    }

    // ── Tokenizer ───────────────────────────────────────────────────────

    private static List<Token> tokenize(final String trace) {
        List<Token> result = new ArrayList<>();
        int i = 0;
        while (i < trace.length()) {
            char c = trace.charAt(i);
            switch (c) {
                case 'C' -> {
                    i++;
                    StringBuilder hex = new StringBuilder();
                    while (i < trace.length() && isHexDigit(trace.charAt(i))) {
                        hex.append(trace.charAt(i++));
                    }
                    result.add(new Token(TokenType.FUNC,
                            Integer.parseInt(hex.toString(), 16)));
                }
                case 'I' -> {
                    result.add(new Token(TokenType.IN, 0));
                    i++;
                }
                case 'O' -> {
                    result.add(new Token(TokenType.OUT, 0));
                    i++;
                }
                case 'R' -> {
                    result.add(new Token(TokenType.RETURN, 0));
                    i++;
                }
                case 'T' -> {
                    result.add(new Token(TokenType.TRY, 0));
                    i++;
                }
                case 'U' -> {
                    result.add(new Token(TokenType.TRY_END, 0));
                    i++;
                }
                case 'J' -> {
                    i++;
                    StringBuilder hex = new StringBuilder();
                    while (i < trace.length() && isHexDigit(trace.charAt(i))) {
                        hex.append(trace.charAt(i++));
                    }
                    result.add(new Token(TokenType.CATCH,
                            Integer.parseInt(hex.toString(), 16)));
                }
                case 'E' -> {
                    result.add(new Token(TokenType.END, 0));
                    i++;
                }
                default -> i++; // skip whitespace / unknown
            }
        }
        return result;
    }

    private static boolean isHexDigit(final char c) {
        // only lowercase hex — uppercase letters are token markers (C, I, O, R, T, U, J, E)
        return (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f');
    }

    // ── Token stream helpers ────────────────────────────────────────────

    private boolean hasMore() {
        return pos < tokens.size();
    }

    private Token peek() {
        return tokens.get(pos);
    }

    private Token consume() {
        return tokens.get(pos++);
    }

    private Token consume(final TokenType expected) {
        Token t = tokens.get(pos++);
        if (t.type() != expected) {
            throw new IllegalStateException(
                    "Expected " + expected + " but got " + t.type()
                            + " at token position " + (pos - 1));
        }
        return t;
    }
}
