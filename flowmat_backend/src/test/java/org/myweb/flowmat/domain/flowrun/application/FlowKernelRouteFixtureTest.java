package org.myweb.flowmat.domain.flowrun.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.flowkernel.FlowKernelFixtures.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.flowkernel.FlowKernelFixtures;
import org.myweb.flowmat.global.exception.BusinessException;

/**
 * The flow kernel's routing fixtures (docs/architecture/adr/ADR-040-flow-kernel.md decisions 4-5): graphs, outputs to
 * send from their nodes, and what the server's runtime graph ({@link FlowRunGraph}) makes of them. For each graph: the
 * published revision it would be ({@code schemaVersion} 3) and whether it reads, its roots, the delays of its connections
 * and, for each Join, how many of its connections the roots can still reach. For each output: what goes along each
 * outgoing connection (reason, quantity, loss), or why the step cannot go on. The kernel's "try it" in the editor
 * repeats these decisions from the start nodes to the end. After a deliberate change, rewrite with
 * {@code ./gradlew test --tests '*FlowKernel*' -Dflow.fixtures.update=true} and read the diff.
 */
class FlowKernelRouteFixtureTest {

    private static final String FILE = "routing.json";
    private static final long SEED = 20261124L;
    private static final int GRAPHS = 120;
    private static final int SCHEMA_VERSION = 3;

    /** Every reason a decision can carry, besides none for an ordinary connection. */
    private static final Set<String> REASONS = Set.of("first_match", "default", "condition_false", "exclusive_not_chosen",
        "default_not_needed", "split", "split_empty", "loss_empty");

    @Test
    void javasRoutesAreTheFixtures() {
        Map<String, JsonNode> graphs = FlowKernelFixtures.handPicked();
        Generator generator = new Generator(new Random(SEED));
        graphs.putAll(FlowKernelFixtures.seeded(FILE, GRAPHS, generator::graph, entry -> entry.get("graph")));
        // Outputs to route: a hand-picked graph's own (cases.json) and then made ones on an update, as written otherwise.
        Map<String, JsonNode> handProbes = new HashMap<>();
        FlowKernelFixtures.read("cases.json").get("cases").forEach(entry -> {
            if (entry.has("probes")) handProbes.put(entry.get("id").asText(), entry.get("probes"));
        });
        Map<String, JsonNode> writtenProbes = new HashMap<>();
        if (!FlowKernelFixtures.UPDATE) {
            FlowKernelFixtures.read(FILE).get("cases")
                .forEach(entry -> writtenProbes.put(entry.get("id").asText(), entry.get("probes")));
        }
        Map<String, Integer> outcomes = new TreeMap<>();
        Map<String, ObjectNode> entries = new LinkedHashMap<>();
        graphs.forEach((id, graph) -> {
            JsonNode snapshot = snapshot(graph);
            ObjectNode entry = MAPPER.createObjectNode();
            entry.set("graph", graph);
            FlowRunGraph runtime;
            try {
                runtime = FlowRunGraph.from(snapshot);
            } catch (BusinessException exception) {
                entry.putArray("probes");
                entry.putObject("expected").set("error", error(exception));
                outcomes.merge("read_error", 1, Integer::sum);
                entries.put(id, entry);
                return;
            }
            ArrayNode probes = MAPPER.createArrayNode();
            if (FlowKernelFixtures.UPDATE) {
                if (handProbes.containsKey(id)) probes.addAll((ArrayNode) handProbes.get(id));
                probes.addAll(new Prober(new Random(SEED ^ id.hashCode())).probes(graph));
            } else {
                probes.addAll((ArrayNode) writtenProbes.getOrDefault(id, MAPPER.createArrayNode()));
            }
            entry.set("probes", probes);
            ObjectNode expected = entry.putObject("expected");
            runtime.roots().forEach(expected.putArray("roots")::add);
            ObjectNode delays = expected.putObject("delays");
            ObjectNode joins = expected.putObject("joins");
            for (JsonNode connection : graph.get("connections")) {
                String connectionId = connection.path("connectionId").asText();
                BigDecimal seconds = runtime.delaySeconds(connectionId);
                if (seconds.signum() == 0) continue;
                ObjectNode delay = delays.putObject(connectionId);
                delay.put("seconds", seconds);
                delay.put("working", runtime.workingDelay(connectionId));
            }
            for (JsonNode process : graph.get("processes")) {
                String processId = process.path("processId").asText();
                if (runtime.join(processId)) joins.put(processId, runtime.stillComing(processId, runtime.roots()));
            }
            ArrayNode answers = expected.putArray("probes");
            for (JsonNode probe : probes) {
                ObjectNode answer = answers.addObject();
                try {
                    List<FlowRunGraph.Decision> decisions = runtime.preview(probe.get("nodeId").asText(), probe.get("output"));
                    answer.set("decisions", MAPPER.valueToTree(decisions));
                    decisions.forEach(decision -> outcomes.merge(
                        decision.reason() == null ? (decision.willRoute() ? "routed" : "condition_false_plain") : decision.reason(),
                        1, Integer::sum));
                    if (decisions.stream().anyMatch(decision -> decision.loss() != null)) outcomes.merge("loss", 1, Integer::sum);
                } catch (BusinessException exception) {
                    answer.set("error", error(exception));
                    outcomes.merge("step_error", 1, Integer::sum);
                }
            }
            entries.put(id, entry);
        });
        ObjectNode header = MAPPER.createObjectNode();
        header.put("seed", SEED);
        header.putObject("context").put("schemaVersion", SCHEMA_VERSION);
        if (FlowKernelFixtures.UPDATE) System.out.printf("%s: %d graphs, outcomes %s%n", FILE, graphs.size(), outcomes);
        FlowKernelFixtures.writeOrCheck(FILE, header, entries);
        assertThat(outcomes.keySet()).as("every reason, loss and both kinds of failure are reached")
            .containsAll(REASONS).contains("routed", "condition_false_plain", "loss", "read_error", "step_error");
    }

    /** The published revision a graph would be: the parts the runtime reads, under the names the revision uses. */
    static JsonNode snapshot(JsonNode graph) {
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("schemaVersion", SCHEMA_VERSION);
        snapshot.set("processes", graph.get("processes"));
        snapshot.set("processIos", graph.get("ports"));
        snapshot.set("connections", graph.get("connections"));
        return snapshot;
    }

    private static ObjectNode error(BusinessException exception) {
        ObjectNode error = MAPPER.createObjectNode();
        error.put("code", exception.getErrorCode().name());
        error.put("message", exception.getMessage());
        return error;
    }

    /** Outputs to send from a graph's nodes: two from each node with somewhere to go, now and then one from a last node. */
    private static final class Prober {

        private static final String[] QUANTITIES = {"0", "1", "2.5", "7", "10", "12.34567", "33.3333", "100", "-3"};

        private final Random random;

        Prober(Random random) {
            this.random = random;
        }

        ArrayNode probes(JsonNode graph) {
            Set<String> sources = new LinkedHashSet<>();
            graph.get("connections").forEach(connection -> sources.add(connection.path("fromProcessId").asText()));
            ArrayNode probes = MAPPER.createArrayNode();
            for (JsonNode process : graph.get("processes")) {
                String processId = process.path("processId").asText();
                int count = sources.contains(processId) ? 2 : random.nextInt(4) == 0 ? 1 : 0;
                for (int index = 0; index < count; index++) {
                    ObjectNode probe = probes.addObject();
                    probe.put("nodeId", processId);
                    probe.set("output", output());
                }
            }
            return probes;
        }

        private JsonNode output() {
            if (random.nextInt(40) == 0) return MAPPER.createArrayNode().add(1);
            ObjectNode output = MAPPER.createObjectNode();
            int quantity = random.nextInt(QUANTITIES.length + 3);
            if (quantity < QUANTITIES.length) output.put("quantity", new BigDecimal(QUANTITIES[quantity]));
            else if (quantity == QUANTITIES.length) output.put("quantity", "ten");
            int unit = random.nextInt(10);
            if (unit < 5) output.put("unit", "kg");
            else if (unit < 7) output.put("unit", "g");
            int item = random.nextInt(6);
            if (item < 2) output.put("item", "item-a");
            else if (item == 2) output.put("item", "item-b");
            if (random.nextInt(3) > 0) {
                ObjectNode attrs = output.putObject("attrs");
                if (random.nextInt(4) > 0) attrs.put("lot", new String[] {"A", "B", ""}[random.nextInt(3)]);
                if (random.nextInt(4) > 0) attrs.put("grade", 1 + random.nextInt(5));
                if (random.nextBoolean()) attrs.put("ok", random.nextBoolean());
                if (random.nextInt(12) == 0) attrs.put("lot", 7);
            } else if (random.nextInt(20) == 0) {
                output.put("attrs", "none");
            }
            return output;
        }
    }

    /**
     * Graphs that mostly publish: steps in order with connections running forward, gateways with several routes, Joins
     * with several branches. Now and then a cycle, a broken condition, an invalid loss rate or failure policy, a Split
     * share left out or a connection to a step that is not there, so the reasons a revision cannot run are reached too.
     */
    private static final class Generator {

        private static final String[] KINDS = {"process", "process", "", "storage", "exclusive", "exclusive", "split",
            "split", "join", "join"};
        private static final String[] CONDITIONS = {"quantity > 5", "attrs.grade >= 3", "attrs.lot = 'A' or quantity < 2",
            "unit = 'kg'", "not (attrs.ok = true)", "item = 'item-a'", "quantity >= 10 and attrs.grade < 4"};
        private static final String[] RULES = {"quantity >= 1", "attrs.lot != ''", "attrs.grade > 2", "unit != 'g'"};
        private static final String[] SCHEMAS = {
            "{\"type\":\"object\",\"properties\":{\"lot\":{\"type\":\"string\"},\"grade\":{\"type\":\"number\"}},\"required\":[\"lot\"]}",
            "{\"type\":\"object\",\"properties\":{\"grade\":{\"type\":\"number\"},\"ok\":{\"type\":\"boolean\"}}}",
        };
        private static final String[] LOSSES = {"0.05", "0.1", "0.25", "0.333", "0.5", "0.99995", "0"};
        private static final String[] DELAYS = {"30", "600", "3600", "0.5", "0"};

        private final Random random;

        Generator(Random random) {
            this.random = random;
        }

        JsonNode graph(int number) {
            ObjectNode graph = MAPPER.createObjectNode();
            ArrayNode processes = graph.putArray("processes");
            ArrayNode ports = graph.putArray("ports");
            ArrayNode connections = graph.putArray("connections");
            int count = 3 + random.nextInt(7);
            List<String> ids = new ArrayList<>();
            List<String> kinds = new ArrayList<>();
            Map<String, String> outputPorts = new HashMap<>();
            Map<String, String> inputPorts = new HashMap<>();
            for (int index = 0; index < count; index++) {
                String id = "n" + number + "_" + index;
                String kind = index == 0 ? "input" : index == count - 1 ? "output" : KINDS[random.nextInt(KINDS.length)];
                ids.add(id);
                kinds.add(kind);
                ObjectNode process = processes.addObject();
                process.put("processId", id);
                process.put("processName", "Step " + index);
                if (!kind.isEmpty()) process.put("nodeType", random.nextInt(12) == 0 ? kind.toUpperCase() : kind);
                if (random.nextInt(3) == 0) outputPorts.put(id, port(ports, id, "output"));
                if (index > 0 && random.nextInt(4) == 0) inputPorts.put(id, port(ports, id, "input"));
            }
            List<int[]> links = new ArrayList<>();
            for (int target = 1; target < count; target++) links.add(new int[] {random.nextInt(target), target});
            for (int index = 0; index < count; index++) {
                String kind = kinds.get(index);
                if (("exclusive".equals(kind) || "split".equals(kind)) && index < count - 1) {
                    int extra = 1 + random.nextInt(3);
                    for (int added = 0; added < extra; added++) links.add(new int[] {index, index + 1 + random.nextInt(count - index - 1)});
                }
                if ("join".equals(kind) && index > 1) {
                    int extra = 1 + random.nextInt(2);
                    for (int added = 0; added < extra; added++) links.add(new int[] {random.nextInt(index), index});
                }
            }
            if (random.nextInt(25) == 0) links.add(new int[] {count - 1, random.nextInt(count - 1)});
            int number2 = 0;
            for (int[] link : links) {
                String from = ids.get(link[0]);
                String to = ids.get(link[1]);
                String fromKind = kinds.get(link[0]);
                ObjectNode connection = connections.addObject();
                connection.put("connectionId", "e" + number + "_" + number2++);
                connection.put("fromProcessId", from);
                connection.put("toProcessId", random.nextInt(80) == 0 ? "gone" : to);
                if (outputPorts.containsKey(from) && random.nextInt(4) > 0) connection.put("fromIoId", outputPorts.get(from));
                if (inputPorts.containsKey(to) && random.nextInt(4) > 0) connection.put("toIoId", inputPorts.get(to));
                if ("exclusive".equals(fromKind)) {
                    if (random.nextInt(3) > 0) connection.put("conditionExpr", condition());
                    if (random.nextBoolean()) connection.put("priority", random.nextInt(3));
                    connection.put("defaultRoute", random.nextInt(4) == 0);
                } else {
                    if (random.nextInt(7) == 0) connection.put("conditionExpr", condition());
                    connection.put("defaultRoute", false);
                }
                if ("split".equals(fromKind)) {
                    int share = random.nextInt(14);
                    if (share < 7) {
                        connection.put("splitKind", "share");
                        connection.put("splitValue", new BigDecimal(new String[] {"10", "20", "30", "40", "50", "60", "33.3333"}[share]));
                    } else if (share < 10) {
                        connection.put("splitKind", "fixed");
                        connection.put("splitValue", new BigDecimal(new String[] {"1", "2.50", "5"}[share - 7]));
                    } else if (share < 13) {
                        connection.put("splitKind", "rest");
                    }
                }
                if (random.nextInt(4) == 0) {
                    String loss = random.nextInt(80) == 0 ? "1.2" : LOSSES[random.nextInt(LOSSES.length)];
                    connection.put("lossRate", new BigDecimal(loss));
                }
                if (random.nextInt(4) == 0) {
                    connection.put("delayTimeSec", new BigDecimal(DELAYS[random.nextInt(DELAYS.length)]));
                    connection.put("delayBasis", random.nextInt(3) == 0 ? "working" : "elapsed");
                }
                if (random.nextInt(8) == 0) connection.put("capacity", new BigDecimal(new String[] {"5", "20", "100"}[random.nextInt(3)]));
                if (random.nextInt(8) == 0) connection.put("unit", random.nextBoolean() ? "kg" : "g");
                if (random.nextInt(4) == 0) {
                    connection.put("failurePolicy", random.nextInt(60) == 0 ? "explode"
                        : new String[] {"stop", "skip", "retry"}[random.nextInt(3)]);
                }
            }
            return graph;
        }

        private String port(ArrayNode ports, String processId, String direction) {
            String id = processId + "_" + direction.charAt(0);
            ObjectNode port = ports.addObject();
            port.put("processIoId", id);
            port.put("processId", processId);
            port.put("direction", direction);
            port.put("resourceType", "data");
            port.put("requiredYn", "N");
            port.put("allowShortageYn", "N");
            if (random.nextInt(4) == 0) port.put("unit", random.nextBoolean() ? "kg" : "g");
            if (random.nextBoolean()) {
                try {
                    port.set("schemaJson", MAPPER.readTree(SCHEMAS[random.nextInt(SCHEMAS.length)]));
                } catch (java.io.IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }
            if (random.nextInt(3) == 0) port.put("validationRule", RULES[random.nextInt(RULES.length)]);
            return id;
        }

        private String condition() {
            return random.nextInt(50) == 0 ? "quantity <" : CONDITIONS[random.nextInt(CONDITIONS.length)];
        }
    }
}
