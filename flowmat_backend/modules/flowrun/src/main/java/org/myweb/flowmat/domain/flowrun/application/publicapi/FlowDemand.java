package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What must go into a graph for a target to come out (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md
 * decisions 1, 3, 6 and 8). The demand is carried backward from the target through ports, connections and gateways, and the
 * answer is then run forward with the run's own rules ({@link FlowShares}) to check that the target arrives.
 *
 * <p>This is the common layer: it knows nodes, ports, connections, quantities and units, and nothing of stock, bills of
 * material, equipment, people or cost. The same answer holds for material through a factory and for records through a data
 * pipeline. Manufacturing's plan builds on it.
 *
 * <p>The rules, from the target back:
 * <ul>
 * <li>A node whose ports have quantities runs in batches: {@code k} = the most any needed output port asks for, divided by
 * its quantity; each input port with a quantity then needs {@code k} &times; its quantity. An input port nothing connects to
 * is a start: what it needs is a {@link Requirement}.</li>
 * <li>A connection's loss {@code r}: what must be sent is what must arrive divided by (1 &minus; r).</li>
 * <li>A Split: the least input that gives every needed connection its share (percent, fixed or rest), and leaves no share
 * negative. A fixed share smaller than its need is a {@link Limit}.</li>
 * <li>An Exclusive: only the chosen connection carries (given, or by default the first by rank that leads to the target). The
 * choice is an {@link Assumption}.</li>
 * <li>Every connection out of the same port gets the whole output, as in a run, so the port needs the most any of them
 * needs. Use a Split to divide.</li>
 * <li>A node without quantities passes what arrives. When several connections arrive at one (a Join gathers them), what each
 * brings follows from the node every branch leaves from: the least amount there that brings enough. Branches that start
 * apart are refused, since nothing says how much each brings.</li>
 * </ul>
 * Amounts are exact while computing ({@link java.math.MathContext#DECIMAL128}); results have 4 decimals: what is needed is
 * rounded up, what is left over HALF_UP. Cycles, formula ports and several targets are refused ({@link Refusal}).
 */
public final class FlowDemand {

    private FlowDemand() {
    }

    /** A published graph as planning reads it. */
    public record Graph(List<Node> nodes, List<Port> ports, List<Connection> connections) {
    }

    /**
     * A node. {@code kind} is its node type: {@code exclusive}, {@code join} and {@code split} are gateways; any other runs in
     * batches when its ports have quantities and passes what arrives when they do not.
     */
    public record Node(String id, String kind, List<ExecutionRequirement> execution) {
        public Node {
            execution = execution == null ? List.of() : List.copyOf(execution);
        }
    }

    /**
     * A port. {@code direction} is {@code input} or {@code output}; {@code quantity} is per batch; {@code resource} is its
     * resource type (ADR-003) and {@code ref} what it holds, such as an item. A port whose {@code formula} is set is refused.
     */
    public record Port(String id, String nodeId, String direction, BigDecimal quantity, String unit, String resource,
        String ref, String formula) {
    }

    /**
     * A connection. {@code lossRate} 0 &le; r &lt; 1; {@code splitKind} {@code share} (percent), {@code fixed} or {@code rest}
     * when it leaves a Split; {@code priority}, then the order given, ranks an Exclusive's connections, its default last.
     */
    public record Connection(String id, String from, String to, String fromPort, String toPort, String condition,
        BigDecimal capacity, BigDecimal lossRate, String splitKind, BigDecimal splitValue, int priority,
        boolean defaultRoute) {
    }

    /**
     * What running a node takes (ADR-043 decision 4). {@code kind} is {@code equipment}, {@code crew}, {@code skill},
     * {@code compute} or {@code time}; {@code per} is {@code batch} or {@code output_unit} (a unit of the node's first output
     * port).
     */
    public record ExecutionRequirement(String kind, String ref, BigDecimal amount, String unit, String per) {
    }

    /** The target: an output port, or a connection whose arrival counts. A null {@code unit} is the port's or connection's. */
    public record Target(String portId, String connectionId, BigDecimal quantity, String unit) {
    }

    /** Converts an amount between two units, empty when it cannot (ADR-043 decision 8). */
    @FunctionalInterface
    public interface Units {
        Optional<BigDecimal> convert(BigDecimal amount, String from, String to);

        /** Converts nothing: units must match. */
        static Units none() {
            return (amount, from, to) -> Optional.empty();
        }
    }

    /** {@code exclusiveChoices}: the connection an Exclusive node takes, by node id; the rest take their default. */
    public record Request(Graph graph, Target target, Map<String, String> exclusiveChoices, Units units) {
        public Request {
            exclusiveChoices = exclusiveChoices == null ? Map.of() : Map.copyOf(exclusiveChoices);
            units = units == null ? Units.none() : units;
        }
    }

    /**
     * The plan. {@code feasible} is false when a {@link Limit} keeps the target from arriving. {@code requirements} are the
     * starts in the graph's order, {@code nodes} the needed nodes.
     */
    public record Result(BigDecimal target, String unit, boolean feasible, List<Requirement> requirements,
        List<NodeDemand> nodes, List<Surplus> surplus, List<Limit> limits, List<Assumption> assumptions) {
    }

    /**
     * What one start needs: an input port nothing connects to ({@code at} is the port), or a node where flow begins ({@code at}
     * is the node). {@code trace} shows how the target became this amount: multiplying the target by every step's
     * {@code factor} gives {@code quantity}.
     */
    public record Requirement(String at, String nodeId, String resource, String ref, BigDecimal quantity, String unit,
        List<Step> trace) {
    }

    /**
     * A needed node: {@code batches} for a node that runs in batches, {@code through} (in {@code unit}) for one that passes
     * what arrives; its ports' amounts and its execution requirements' totals.
     */
    public record NodeDemand(String nodeId, BigDecimal batches, BigDecimal through, String unit, List<PortDemand> ports,
        List<ExecutionTotal> execution) {
    }

    /** An input port's need or an output port's yield for the node's batches. */
    public record PortDemand(String portId, String direction, BigDecimal quantity, String unit) {
    }

    /** An execution requirement's total for the plan. */
    public record ExecutionTotal(String kind, String ref, BigDecimal amount, String unit) {
    }

    /**
     * One step of a trace, at a node, port or connection. {@code rule} is {@code raised} (the target planned for more so
     * that it still arrives after the run's rounding), {@code ratio}, {@code loss}, {@code unit}, {@code split-share},
     * {@code split-fixed}, {@code split-rest}, {@code exclusive}, {@code pass}, {@code join} or {@code round};
     * {@code factor} = {@code to} / {@code from}, unrounded.
     */
    public record Step(String at, String rule, BigDecimal from, BigDecimal to, BigDecimal factor) {
    }

    /** What the plan makes beyond the need: {@code kind} is {@code byproduct}, {@code over} or {@code split-left}. */
    public record Surplus(String at, String kind, BigDecimal quantity, String unit) {
    }

    /**
     * Why the target cannot arrive as planned: {@code kind} is {@code split-fixed}, {@code exclusive}, {@code capacity},
     * {@code rounding} (still short after raising), or {@code forward-check} (running the plan forward brings clearly less than
     * the target, which rounding cannot explain).
     */
    public record Limit(String at, String kind, String message) {
    }

    /** What the plan took for granted: {@code kind} is {@code exclusive}, {@code condition} or {@code raised}. */
    public record Assumption(String at, String kind, String value) {
    }

    /** A graph or target this cannot plan; {@code at} is the node, port or connection to fix. */
    public static final class Refusal extends RuntimeException {
        private final String at;

        public Refusal(String at, String message) {
            super(message);
            this.at = at;
        }

        public String at() {
            return at;
        }
    }

    /** Plans {@code request}; a {@link Refusal} when the graph or target cannot be planned. */
    public static Result plan(Request request) {
        return new FlowDemandPlanner(request).plan();
    }
}
