package prorunvis.trace.process;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import org.junit.jupiter.api.Test;
import prorunvis.Tester;
import prorunvis.instrument.Instrumenter;
import prorunvis.preprocess.Preprocessor;

import java.io.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class AbstractRetracerTest extends Tester {

    private final String testDir = "src/test/testfiles/abstractretracer/";

    @Test
    void demoTest() throws IOException {
        process(testDir + "demo/resources",
                testDir + "demo/trace.txt",
                testDir + "demosolution/expectedTraceNodes.tr");
    }

    @Test
    void ifElseTest() throws IOException {
        process(testDir + "ifelse/resources",
                testDir + "ifelse/trace.txt",
                testDir + "ifelsesolution/expectedTraceNodes.tr");
    }

    /**
     * Runs the AbstractRetracer with a pre-recorded src-tracer trace
     * and compares the resulting TraceNode tree to an expected output.
     */
    private void process(final String resourcePath,
                         final String traceFilePath,
                         final String solutionPath) throws IOException {

        Path rootDir = Paths.get(resourcePath + "/in");
        ProjectRoot projectRoot = new SymbolSolverCollectionStrategy()
                .collect(rootDir.toAbsolutePath());
        List<CompilationUnit> cus = createCompilationUnits(projectRoot);

        File traceFile = new File(resourcePath + "/traceFile.tr");
        Instrumenter.setupTrace(traceFile);

        Map<Integer, Node> map = new HashMap<>();
        cus.forEach(cu -> {
            Preprocessor.run(cu);
            Instrumenter.run(cu, map);
        });

        // Abstract retrace: convert src-tracer trace → block IDs
        AbstractRetracer retracer = new AbstractRetracer(
                cus, map, traceFilePath, rootDir);
        Stack<Integer> retracedTokens = retracer.retrace();

        // Feed into TraceProcessor to build TraceNode tree
        TraceProcessor processor = new TraceProcessor(map, retracedTokens, rootDir);
        processor.start();

        // Compare output to expected
        BufferedReader solutionReader = new BufferedReader(new FileReader(solutionPath));
        List<String> expectedResult = solutionReader.lines().toList();
        solutionReader.close();

        List<String> actualResult = Arrays.asList(processor.toString().split("\n"));

        assertIterableEquals(expectedResult, actualResult);
    }
}
