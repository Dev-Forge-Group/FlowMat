package org.myweb.flowmat.domain.workflow.domain.expression;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.myweb.flowmat.global.exception.BusinessException;

/**
 * The Java half of the condition language's timing comparison; {@code examples/bench.rs} in
 * {@code compute/condition-expression} is the Rust half, and {@code scripts/perf/condition-bench.sh} runs both. Both
 * compile every source of {@code generated.json} and evaluate every compiled one against every snapshot, round after
 * round, and report the median round and the bytes allocated per operation. Off unless {@code -Dcondition.bench=true}.
 */
@EnabledIfSystemProperty(named = "condition.bench", matches = "true")
class ConditionExpressionBenchmarkTest {

    private static final int WARM_UP_ROUNDS = 30;
    private static final int MEASURED_ROUNDS = 30;

    @Test
    void timesCompilingAndEvaluatingTheGeneratedCorpus() throws IOException {
        Path fixtures = Path.of("..", "compute", "condition-expression", "fixtures");
        JsonNode corpus = ConditionExpressionFixtureTest.SNAPSHOTS.readTree(Files.readString(fixtures.resolve("generated.json")));
        List<String> sources = new ArrayList<>();
        corpus.get("cases").forEach(entry -> sources.add(entry.get("source").asText()));
        List<Map<String, Object>> snapshots = new ArrayList<>();
        corpus.get("snapshots").forEach(snapshot -> snapshots.add(ConditionExpressionFixtureTest.values(snapshot)));
        List<ConditionExpression> compiled = compileAll(sources);
        int expectedTrue = evaluateAll(compiled, snapshots);
        long evaluations = (long) compiled.size() * snapshots.size();

        com.sun.management.ThreadMXBean threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        for (int round = 0; round < WARM_UP_ROUNDS; round++) {
            compileAll(sources);
            assertThat(evaluateAll(compiled, snapshots)).isEqualTo(expectedTrue);
        }
        long[] compileNanos = new long[MEASURED_ROUNDS];
        long[] evaluateNanos = new long[MEASURED_ROUNDS];
        long compileBytes = 0;
        long evaluateBytes = 0;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            long allocated = threads.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            compileAll(sources);
            compileNanos[round] = System.nanoTime() - started;
            compileBytes += threads.getThreadAllocatedBytes(thread) - allocated;

            allocated = threads.getThreadAllocatedBytes(thread);
            started = System.nanoTime();
            int answered = evaluateAll(compiled, snapshots);
            evaluateNanos[round] = System.nanoTime() - started;
            evaluateBytes += threads.getThreadAllocatedBytes(thread) - allocated;
            assertThat(answered).isEqualTo(expectedTrue);
        }

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("implementation", "java " + Runtime.version());
        report.put("sources", sources.size());
        report.put("compiled", compiled.size());
        report.put("evaluations", evaluations);
        report.put("trueAnswers", expectedTrue);
        report.put("compileNanosPerSource", median(compileNanos) / sources.size());
        report.put("evaluateNanosPerEvaluation", median(evaluateNanos) / evaluations);
        report.put("compileBytesPerSource", compileBytes / MEASURED_ROUNDS / sources.size());
        report.put("evaluateBytesPerEvaluation", evaluateBytes / MEASURED_ROUNDS / evaluations);
        report.put("peakResidentBytes", peakResidentBytes());
        report.put("heapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        Path out = Path.of("build", "condition-bench", "java.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        System.out.println(report);
    }

    private static List<ConditionExpression> compileAll(List<String> sources) {
        List<ConditionExpression> compiled = new ArrayList<>(sources.size());
        for (String source : sources) {
            try {
                compiled.add(ConditionExpression.compile(source));
            } catch (BusinessException ignored) {
                // An error is an answer too: its message is built, as Rust builds its error.
            }
        }
        return compiled;
    }

    private static int evaluateAll(List<ConditionExpression> compiled, List<Map<String, Object>> snapshots) {
        int answered = 0;
        for (ConditionExpression expression : compiled) {
            for (Map<String, Object> values : snapshots) {
                if (expression.evaluate(values)) answered++;
            }
        }
        return answered;
    }

    /** The test JVM's peak resident set (Linux VmHWM), or null elsewhere. It holds JUnit and Jackson as well. */
    private static Long peakResidentBytes() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmHWM:")) return Long.parseLong(line.split("\\s+")[1]) * 1024;
            }
        } catch (IOException | RuntimeException ignored) {
            // Not Linux.
        }
        return null;
    }

    private static double median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted.length % 2 == 1 ? sorted[sorted.length / 2] : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2.0;
    }
}
