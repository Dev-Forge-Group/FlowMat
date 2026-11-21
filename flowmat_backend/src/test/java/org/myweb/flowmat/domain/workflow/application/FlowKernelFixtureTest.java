package org.myweb.flowmat.domain.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.myweb.flowmat.flowkernel.FlowKernelFixtures.MAPPER;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowValidationResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessConnection;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessIo;
import org.myweb.flowmat.flowkernel.FlowKernelFixtures;

/**
 * The flow kernel's validation fixtures (docs/architecture/adr/ADR-040-flow-kernel.md decision 5): hand-picked graphs and
 * graphs from a fixed seed, and what {@link WorkflowValidationService#check} says of each. Rust and its WebAssembly build
 * must answer every graph the same way. Every one of the check's issue codes is hit, so a rule the kernel gets wrong
 * cannot hide. After a deliberate change to the check, rewrite the answers with
 * {@code ./gradlew test --tests '*FlowKernel*' -Dflow.fixtures.update=true} and read the diff.
 */
class FlowKernelFixtureTest {

    private static final String FILE = "validation.json";
    private static final long SEED = 20261123L;
    private static final int GRAPHS = 160;

    /** The units a graph may name, by type; anything else is unknown. */
    static final Map<String, String> KNOWN_UNITS = Map.of("kg", "mass", "g", "mass", "ea", "count", "l", "volume", "m", "length");

    /** Every issue code the check can report. */
    private static final Set<String> CODES = Set.of("CONNECTION_INCOMPATIBLE", "CONNECTION_ORPHAN", "CYCLE",
        "DEFAULT_ROUTE_CONDITION_IGNORED", "DEFAULT_ROUTE_OUTSIDE_GATEWAY", "DELAY_RANGE", "EXPRESSION_INVALID",
        "GATEWAY_DEFAULTS", "GATEWAY_PRIORITY_TIE", "GATEWAY_SINGLE_ROUTE", "GATEWAY_WITHOUT_INPUT", "JOIN_SINGLE_INPUT",
        "JOIN_WITHOUT_INPUT", "LOSS_RATE_RANGE", "PORT_INVALID", "PORT_SCHEMA_INVALID", "PROCESS_ISOLATED",
        "REQUIRED_INPUT_UNCONNECTED", "RESOURCE_TYPE_UNKNOWN", "SCHEMA_UNVERIFIED", "SPLIT_CONDITION", "SPLIT_NO_REST",
        "SPLIT_OUTSIDE_GATEWAY", "SPLIT_REST_DUPLICATE", "SPLIT_SHARES_OVER", "SPLIT_SHARE_MISSING", "SPLIT_SINGLE_ROUTE",
        "SPLIT_WITHOUT_INPUT", "UNEXPECTED_ROOT", "UNEXPECTED_TERMINAL", "UNIT_UNKNOWN");

    @Test
    void javasAnswersAreTheFixturesAndEveryRuleIsHit() {
        Map<String, JsonNode> graphs = FlowKernelFixtures.handPicked();
        Generator generator = new Generator(new Random(SEED));
        graphs.putAll(FlowKernelFixtures.seeded(FILE, GRAPHS, generator::graph, entry -> entry.get("graph")));
        Map<String, Integer> hits = new TreeMap<>();
        Map<String, ObjectNode> entries = new LinkedHashMap<>();
        graphs.forEach((id, graph) -> {
            WorkflowValidationResponse answer = answer(graph);
            answer.issues().forEach(issue -> hits.merge(issue.code(), 1, Integer::sum));
            ObjectNode entry = MAPPER.createObjectNode();
            entry.set("graph", graph);
            entry.set("expected", MAPPER.valueToTree(answer));
            entries.put(id, entry);
        });
        ObjectNode header = MAPPER.createObjectNode();
        header.put("seed", SEED);
        header.set("context", context());
        if (FlowKernelFixtures.UPDATE) System.out.printf("%s: %d graphs, issues by code %s%n", FILE, graphs.size(), hits);
        FlowKernelFixtures.writeOrCheck(FILE, header, entries);
        assertThat(hits.keySet()).as("every issue code is hit by some graph").containsAll(CODES);
    }

    /** What the server's check says of a graph given as the kernel takes it, in the server's response shape. */
    static WorkflowValidationResponse answer(JsonNode graph) {
        List<Process> processes = new ArrayList<>();
        graph.get("processes").forEach(node -> processes.add(MAPPER.convertValue(node, Process.class)));
        List<ProcessIo> ports = new ArrayList<>();
        graph.get("ports").forEach(node -> ports.add(MAPPER.convertValue(stored(node), ProcessIo.class)));
        List<ProcessConnection> connections = new ArrayList<>();
        graph.get("connections").forEach(node -> connections.add(MAPPER.convertValue(stored(node), ProcessConnection.class)));
        return WorkflowValidationService.check(processes, ports, connections, new WorkflowUnits() {
            // As CatalogQueryImpl looks a code up in unit_master: ignoring case, and for "known" surrounding spaces too.
            @Override
            public boolean known(String code) {
                return code != null && type(code.trim()).isPresent();
            }

            @Override
            public Optional<String> type(String code) {
                if (code == null) return Optional.empty();
                return KNOWN_UNITS.entrySet().stream().filter(unit -> unit.getKey().equalsIgnoreCase(code))
                    .map(Map.Entry::getValue).findFirst();
            }
        });
    }

    /** A port or connection as stored: the schema as text and the default route as Y or N. */
    private static ObjectNode stored(JsonNode response) {
        ObjectNode row = response.deepCopy();
        JsonNode schema = row.remove("schemaJson");
        if (schema != null && !schema.isNull()) row.put("schemaJson", FlowKernelFixtures.json(schema));
        JsonNode defaultRoute = row.remove("defaultRoute");
        if (defaultRoute != null) row.put("defaultRouteYn", defaultRoute.asBoolean() ? "Y" : "N");
        return row;
    }

    private static ObjectNode context() {
        ObjectNode context = MAPPER.createObjectNode();
        ObjectNode units = context.putObject("knownUnits");
        new TreeSet<>(KNOWN_UNITS.keySet()).forEach(code -> units.put(code, KNOWN_UNITS.get(code)));
        return context;
    }

    /**
     * Graphs that mostly make sense and now and then do not, so every rule is reached. {@link Random} is specified to the
     * bit, so a seed gives the same graphs on every JDK.
     */
    private static final class Generator {

        private static final String[] KINDS = {"process", "process", "process", "", "input", "output", "storage", "exclusive",
            "exclusive", "join", "split", "split", "decision"};
        private static final String[] RESOURCES = {"material", "product", "data", "energy", "water", "signal", "generic",
            "plasma"};
        private static final String[] UNITS = {"kg", "g", "ea", "l", "m", "lbx", "KG", " kg", "Ea"};
        private static final String[] SCHEMAS = {
            "{\"type\":\"object\",\"properties\":{\"lot\":{\"type\":\"string\"},\"grade\":{\"type\":\"number\"}},\"required\":[\"lot\"]}",
            "{\"type\":\"object\",\"properties\":{\"lot\":{\"type\":\"string\"}}}",
            "{\"type\":\"object\",\"properties\":{\"grade\":{\"type\":\"string\"},\"ok\":{\"type\":\"boolean\"}},\"required\":[\"grade\"]}",
            "{\"type\":\"object\",\"properties\":{\"lot\":{\"type\":\"number\"}},\"required\":[\"lot\"]}",
            "{\"type\":\"array\"}",
            "{\"type\":\"object\",\"properties\":{\"1bad\":{\"type\":\"string\"}}}",
        };
        private static final String[] RULES = {"attrs.grade > 2", "quantity >= 1 and attrs.lot != ''", "attrs.color = 'red'",
            "quantity >>", "not (attrs.ok = true)", "unit = 'kg'"};
        private static final String[] CONDITIONS = {"quantity > 5", "attrs.grade >= 3", "attrs.lot = 'A' or quantity < 2",
            "attrs.missing = 1", "quantity <", "item != ''"};

        private final Random random;

        Generator(Random random) {
            this.random = random;
        }

        JsonNode graph(int number) {
            ObjectNode graph = MAPPER.createObjectNode();
            ArrayNode processes = graph.putArray("processes");
            ArrayNode ports = graph.putArray("ports");
            ArrayNode connections = graph.putArray("connections");
            int processCount = 2 + random.nextInt(8);
            List<String> processIds = new ArrayList<>();
            List<String> gateways = new ArrayList<>();
            Map<String, List<String>> outputs = new LinkedHashMap<>();
            Map<String, List<String>> inputs = new LinkedHashMap<>();
            List<String> allPorts = new ArrayList<>();
            for (int index = 0; index < processCount; index++) {
                String id = "p" + number + "_" + index;
                processIds.add(id);
                ObjectNode process = processes.addObject();
                process.put("processId", id);
                if (random.nextInt(5) > 0) process.put("processName", "Step " + index);
                String kind = pick(KINDS);
                if (!kind.isEmpty()) process.put("nodeType", random.nextInt(10) == 0 ? kind.toUpperCase() : kind);
                if ("exclusive".equals(kind) || "split".equals(kind)) gateways.add(id);
                outputs.put(id, new ArrayList<>());
                inputs.put(id, new ArrayList<>());
                int portCount = random.nextInt(4);
                for (int portIndex = 0; portIndex < portCount; portIndex++) {
                    ObjectNode port = ports.addObject();
                    String portId = id + "_io" + portIndex;
                    port.put("processIoId", portId);
                    port.put("processId", id);
                    String direction = random.nextInt(30) == 0 ? "sideways" : random.nextBoolean() ? "input" : "output";
                    port.put("direction", direction);
                    if (random.nextInt(3) > 0) port.put("ioName", "port " + portIndex);
                    String resource = random.nextInt(4) == 0 ? pick(RESOURCES) : random.nextBoolean() ? "data" : "generic";
                    if (random.nextInt(40) > 0) port.put("resourceType", random.nextInt(60) == 0 ? " " : resource);
                    if (random.nextInt(6) == 0) port.put("itemId", random.nextBoolean() ? "item-a" : "item-b");
                    boolean measured = "material".equals(resource) || "product".equals(resource) || port.has("itemId");
                    if (measured ? random.nextInt(12) > 0 : random.nextInt(4) == 0) {
                        port.put("quantity", random.nextInt(40) == 0 ? new BigDecimal("-1") : new BigDecimal(random.nextInt(50) + 1));
                        port.put("unit", pick(UNITS));
                    } else if (random.nextInt(8) == 0) {
                        port.put("unit", pick(UNITS));
                    }
                    port.put("requiredYn", random.nextInt(40) == 0 ? "X" : random.nextInt(3) == 0 ? "Y" : "N");
                    port.put("allowShortageYn", random.nextBoolean() ? "N" : "Y");
                    if (random.nextInt(5) < 3) port.set("schemaJson", schema(pick(SCHEMAS.length - 2, SCHEMAS, 25)));
                    if (random.nextInt(6) == 0) port.put("validationRule", pick(RULES));
                    allPorts.add(portId);
                    if ("output".equals(direction)) outputs.get(id).add(portId);
                    if ("input".equals(direction)) inputs.get(id).add(portId);
                }
            }
            int connectionCount = random.nextInt(processCount + 4);
            for (int index = 0; index < connectionCount; index++) {
                ObjectNode connection = connections.addObject();
                connection.put("connectionId", "c" + number + "_" + index);
                connection.put("workflowId", "wf" + number);
                // A third of the connections leave a gateway, so gateways have the several routes their rules are about.
                String from = random.nextInt(40) == 0 ? "gone"
                    : !gateways.isEmpty() && random.nextInt(3) == 0 ? gateways.get(random.nextInt(gateways.size()))
                    : processIds.get(random.nextInt(processCount));
                String to = random.nextInt(25) == 0 ? from : processIds.get(random.nextInt(processCount));
                connection.put("fromProcessId", from);
                connection.put("toProcessId", to);
                String fromPort = choosePort(outputs.getOrDefault(from, List.of()), allPorts);
                String toPort = choosePort(inputs.getOrDefault(to, List.of()), allPorts);
                if (fromPort != null) connection.put("fromIoId", fromPort);
                if (toPort != null) connection.put("toIoId", toPort);
                if (random.nextInt(7) == 0) connection.put("conditionExpr", pick(CONDITIONS));
                if (random.nextInt(10) == 0) connection.put("unit", pick(UNITS));
                int delay = random.nextInt(20);
                if (delay < 3) connection.put("delayTimeSec", delay == 0 ? new BigDecimal("-30") : new BigDecimal(60 * delay));
                int loss = random.nextInt(20);
                if (loss < 4) connection.put("lossRate", new BigDecimal(new String[] {"0.05", "1.5", "-0.2", "5"}[loss]));
                if (random.nextInt(3) == 0) connection.put("priority", random.nextInt(3));
                connection.put("defaultRoute", random.nextInt(6) == 0);
                int share = random.nextInt(6);
                if (share < 3) {
                    String kind = new String[] {"share", "rest", "fixed"}[share];
                    connection.put("splitKind", kind);
                    if ("share".equals(kind)) connection.put("splitValue", new BigDecimal(10 * (1 + random.nextInt(8))));
                    if ("fixed".equals(kind)) connection.put("splitValue", new BigDecimal(1 + random.nextInt(5)));
                }
            }
            return graph;
        }

        /** Mostly a fitting port, sometimes none, a port of another process or one that is gone. */
        private String choosePort(List<String> fitting, List<String> all) {
            int roll = random.nextInt(20);
            if (roll == 0) return "missing-port";
            if (roll == 1 && !all.isEmpty()) return all.get(random.nextInt(all.size()));
            if (roll < 6 || fitting.isEmpty()) return null;
            return fitting.get(random.nextInt(fitting.size()));
        }

        private static JsonNode schema(String text) {
            try {
                return MAPPER.readTree(text);
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(exception);
            }
        }

        private String pick(String[] values) {
            return values[random.nextInt(values.length)];
        }

        /** One of the first {@code common} values, or one in {@code rarePercent} percent of draws from the rest. */
        private String pick(int common, String[] values, int rarePercent) {
            if (random.nextInt(100) < rarePercent) return values[common + random.nextInt(values.length - common)];
            return values[random.nextInt(common)];
        }
    }
}
