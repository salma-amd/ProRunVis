package api.functionality.process;

import api.upload.storage.StorageProperties;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.github.javaparser.symbolsolver.utils.SymbolSolverCollectionStrategy;
import com.github.javaparser.utils.ProjectRoot;
import com.google.gson.Gson;
import org.springframework.stereotype.Service;
import prorunvis.CompileAndRun;
import prorunvis.instrument.Instrumenter;
import prorunvis.preprocess.Preprocessor;
import prorunvis.trace.TraceNode;
import prorunvis.trace.process.AbstractRetracer;
import prorunvis.trace.process.TraceProcessor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

@Service
public final class SingleRunProcessingService implements ProcessingService {

    private final Path inLocation;
    private final Path outLocation;
    private List<CompilationUnit> cus;
    private File traceFile;
    private HashMap<Integer, Node> traceMap;
    private List<TraceNode> nodes;

    public SingleRunProcessingService(final StorageProperties properties) {
        if (properties.getLocation().trim().isEmpty()) {
            throw new ProcessingException("Cannot process empty directory.");
        }

        inLocation = Paths.get(properties.getLocation());
        outLocation = Paths.get(properties.getOutLocation());
    }

    @Override
    public boolean isReady() {
        return inLocation.toFile().exists()
                && inLocation.toFile().isDirectory()
                && Objects.requireNonNull(inLocation.toFile().listFiles()).length > 0;
    }

    @Override
    public void instrument() {
        StaticJavaParser.getParserConfiguration().setSymbolResolver(new JavaSymbolSolver(new CombinedTypeSolver()));
        ProjectRoot projectRoot =
                new SymbolSolverCollectionStrategy().collect(inLocation);

        traceFile = new File(outLocation.toString() + "/Trace.tr");
        Instrumenter.setupTrace(traceFile);

        cus = new ArrayList<>();
        projectRoot.getSourceRoots().forEach(sr -> {
            try {
                sr.tryToParse().forEach(cu -> cus.add(cu.getResult().orElseThrow()));
            } catch (IOException | NoSuchElementException e) {
                throw new ProcessingException("Cannot parse provided input.", e);
            }
        });

        traceMap = new HashMap<>();
        cus.forEach(cu -> {
            Preprocessor.run(cu);
            Instrumenter.run(cu, traceMap);
        });
        Instrumenter.saveInstrumented(projectRoot, outLocation.toString() + "/instrumented");
    }

    @Override
    public void trace() {
        if (findSrcTracerTrace() != null) {
            return;
        }

        try {
            CompileAndRun.run(cus, outLocation.toString() + "/instrumented",
                    outLocation.toString() + "/compiled");
        } catch (IOException | InterruptedException | ArrayIndexOutOfBoundsException e) {
            throw new ProcessingException(e.getMessage());
        }
    }

    @Override
    public void process() {
        TraceProcessor processor;
        String srcTracerTrace = findSrcTracerTrace();

        if (srcTracerTrace != null) {
            System.out.println("[ProRunVis] Using SrcTracer path: " + srcTracerTrace);
            try {
                AbstractRetracer retracer = new AbstractRetracer(
                        cus, traceMap, srcTracerTrace, inLocation);
                Stack<Integer> retraced = retracer.retrace();
                processor = new TraceProcessor(traceMap, retraced, inLocation);
            } catch (IOException e) {
                throw new ProcessingException("Failed to read SrcTracer trace file.", e);
            } catch (IllegalStateException e) {
                throw new ProcessingException(e.getMessage(), e);
            }
        } else {
            System.out.println("[ProRunVis] Using original compile-and-run path");
            processor = new TraceProcessor(traceMap, traceFile.getPath(), inLocation);
        }

        try {
            processor.start();
        } catch (IOException e) {
            throw new ProcessingException("An error occurred during processing of the trace.", e);
        }
        nodes = processor.getNodeList();
    }

    @Override
    public String toJSON() {
        Gson gson = new Gson();
        String response = gson.toJson(nodes);
        return response.replace("\\\\", "/");
    }

    private String findSrcTracerTrace() {
        try (Stream<Path> paths = Files.walk(inLocation)) {
            return paths
                    .filter(p -> p.toString().endsWith(".trace.txt")
                            || p.toString().endsWith(".trace"))
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
