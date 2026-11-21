package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.flowkernel.FlowKernelFixtures.MAPPER;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.ExportFunction;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.myweb.flowmat.flowkernel.FlowKernelFixtures;

/**
 * The measurement of docs/architecture/adr/ADR-040-flow-kernel.md decision 6: could the server check a draft with the
 * flow kernel's WebAssembly module, run in the JVM by Chicory, instead of WorkflowValidationService's Java? Every graph
 * of compute/flow-kernel/fixtures/validation.json goes through both. The answers must be the same, and one check's
 * median and p95 time are compared. Java's time is check() on the stored rows. The module's time also covers writing the
 * graph as JSON and reading the answer back, which the server would have to do. Chicory runs the module both
 * interpreted and compiled to JVM bytecode. Off unless {@code -Dflow.bench=true}; the numbers go into
 * docs/operations/performance-baseline.md.
 */
@EnabledIfSystemProperty(named = "flow.bench", matches = "true")
class FlowKernelChicoryBenchmarkTest {

    private static final int WARMUP_ROUNDS = 20;
    private static final int ROUNDS = 30;
    /** The interpreter is far slower; fewer rounds still give a stable median. */
    private static final int INTERPRETED_WARMUP_ROUNDS = 2;
    private static final int INTERPRETED_ROUNDS = 3;

    @Test
    void theKernelInChicoryAgainstJava() throws Exception {
        JsonNode file = FlowKernelFixtures.read("validation.json");
        List<JsonNode> graphs = new ArrayList<>();
        file.get("cases").forEach(entry -> graphs.add(entry.get("graph")));
        List<FlowKernelFixtureTest.Stored> stored = graphs.stream().map(FlowKernelFixtureTest::storedGraph).toList();
        List<byte[]> requests = new ArrayList<>();
        for (JsonNode graph : graphs) {
            ObjectNode request = MAPPER.createObjectNode();
            request.set("graph", graph);
            request.set("knownUnits", file.get("context").get("knownUnits"));
            requests.add(MAPPER.writeValueAsBytes(request));
        }
        byte[] wasm = Files.readAllBytes(locate(Path.of("flowmat_frontend", "src", "shared", "wasm", "flowmat_flow_kernel.wasm")));
        WasmModule module = Parser.parse(wasm);

        Map<String, Kernel> kernels = new LinkedHashMap<>();
        long started = System.nanoTime();
        kernels.put("interpreted", new Kernel(Instance.builder(module).build()));
        long interpretedLoad = System.nanoTime() - started;
        started = System.nanoTime();
        kernels.put("compiled", new Kernel(Instance.builder(module)
            .withMachineFactory(MachineFactoryCompiler.compile(module)).build()));
        long compiledLoad = System.nanoTime() - started;

        // Same answers first.
        for (Map.Entry<String, Kernel> kernel : kernels.entrySet()) {
            for (int index = 0; index < graphs.size(); index++) {
                // Through JSON text, as the server would send it, so 130 is a number however Java held it.
                JsonNode java = MAPPER.readTree(MAPPER.writeValueAsString(FlowKernelFixtureTest.answer(graphs.get(index))));
                assertThat(kernel.getValue().validate(requests.get(index)))
                    .as(kernel.getKey() + " " + file.get("cases").get(index).get("id").asText()).isEqualTo(java);
            }
        }

        Map<String, long[]> times = new LinkedHashMap<>();
        times.put("java", time(graphs.size(), WARMUP_ROUNDS, ROUNDS, index -> {
            FlowKernelFixtureTest.Stored rows = stored.get(index);
            return WorkflowValidationService.check(rows.processes(), rows.ports(), rows.connections(),
                FlowKernelFixtureTest.UNITS);
        }));
        for (Map.Entry<String, Kernel> kernel : kernels.entrySet()) {
            boolean interpreted = "interpreted".equals(kernel.getKey());
            times.put(kernel.getKey(), time(graphs.size(), interpreted ? INTERPRETED_WARMUP_ROUNDS : WARMUP_ROUNDS,
                interpreted ? INTERPRETED_ROUNDS : ROUNDS, index -> kernel.getValue().validate(requests.get(index))));
        }
        long[] java = times.get("java");
        StringBuilder report = new StringBuilder("flow kernel validation, " + graphs.size() + " graphs x " + ROUNDS
            + " rounds (interpreted " + INTERPRETED_ROUNDS + "), per check (microseconds):\n");
        for (Map.Entry<String, long[]> entry : times.entrySet()) {
            long[] sample = entry.getValue();
            report.append(String.format("  %-12s median %8.1f  p95 %8.1f  (x%.1f / x%.1f of Java)%n", entry.getKey(),
                percentile(sample, 50) / 1000.0, percentile(sample, 95) / 1000.0,
                (double) percentile(sample, 50) / percentile(java, 50), (double) percentile(sample, 95) / percentile(java, 95)));
        }
        report.append(String.format("  load: interpreted %.0f ms, compiled %.0f ms; module memory %d pages (%d KiB) after the run%n",
            interpretedLoad / 1e6, compiledLoad / 1e6, kernels.get("compiled").instance.memory().pages(),
            kernels.get("compiled").instance.memory().pages() * 64));
        System.out.print(report);
    }

    /** Each graph's check timed one by one, after warming up; all the times of the measured rounds. */
    private static long[] time(int graphs, int warmups, int rounds, Function<Integer, Object> check) {
        for (int round = 0; round < warmups; round++) {
            for (int index = 0; index < graphs; index++) check.apply(index);
        }
        long[] sample = new long[graphs * rounds];
        int at = 0;
        for (int round = 0; round < rounds; round++) {
            for (int index = 0; index < graphs; index++) {
                long started = System.nanoTime();
                check.apply(index);
                sample[at++] = System.nanoTime() - started;
            }
        }
        return sample;
    }

    private static long percentile(long[] sample, int percent) {
        long[] sorted = sample.clone();
        Arrays.sort(sorted);
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(percent / 100.0 * sorted.length) - 1)];
    }

    private static Path locate(Path relative) {
        for (Path directory = Path.of("").toAbsolutePath(); directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(relative))) return directory.resolve(relative);
        }
        throw new IllegalStateException(relative + " was not found");
    }

    /** The module's validate export, over one instance: JSON in, JSON out, the memory given back. */
    private static final class Kernel {
        private final Instance instance;
        private final ExportFunction alloc;
        private final ExportFunction dealloc;
        private final ExportFunction validate;

        Kernel(Instance instance) {
            this.instance = instance;
            this.alloc = instance.export("alloc");
            this.dealloc = instance.export("dealloc");
            this.validate = instance.export("validate");
        }

        JsonNode validate(byte[] request) {
            long pointer = alloc.apply(request.length)[0];
            instance.memory().write((int) pointer, request);
            long packed = validate.apply(pointer, request.length)[0];
            dealloc.apply(pointer, request.length);
            int at = (int) (packed >>> 32);
            int length = (int) packed;
            String answer = new String(instance.memory().readBytes(at, length), StandardCharsets.UTF_8);
            dealloc.apply(at, length);
            try {
                return MAPPER.readTree(answer);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
