package org.myweb.flowmat.domain.flowrun.application.publicapi;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Assumption;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Connection;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.ExecutionRequirement;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.ExecutionTotal;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Limit;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Node;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.NodeDemand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Port;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.PortDemand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Refusal;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Requirement;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Result;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Step;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Surplus;

/**
 * {@link FlowDemand}'s calculation: the graph's indexes, then one backward pass per target tried and a forward check. A loop a
 * run can go round is planned through (docs/architecture/adr/ADR-048-plan-through-loops.md).
 */
final class FlowDemandPlanner {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final String VIRTUAL = "";
    private static final String BATCH = "batch";
    /** How many times a target is raised when rounding on the way leaves it short. */
    private static final int RAISES = 10;
    /** The most of the target, as a fraction, that the run's rounding may leave short before a plan is raised for it. */
    private static final BigDecimal ROUNDING_SHORTFALL = new BigDecimal("0.0001");

    private final FlowDemand.Request request;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final Map<String, Port> ports = new LinkedHashMap<>();
    private final Map<String, Connection> connections = new LinkedHashMap<>();
    private final Map<String, Integer> drawn = new HashMap<>();
    private final Map<String, Integer> portPosition = new HashMap<>();
    private final Map<String, List<Port>> inputs = new HashMap<>();
    private final Map<String, List<Port>> outputs = new HashMap<>();
    private final Map<String, List<Connection>> outgoing = new HashMap<>();
    private final Map<String, List<Connection>> incoming = new HashMap<>();
    private final Map<String, List<Connection>> intoPort = new HashMap<>();
    private final Map<String, List<Connection>> fromPort = new HashMap<>();
    private final List<String> order = new ArrayList<>();
    private final Map<String, Integer> position = new HashMap<>();
    private final Map<String, String> idom = new HashMap<>();
    private final Map<String, Integer> depth = new HashMap<>();
    private final Map<String, String> choices = new HashMap<>();
    private final Set<String> leadsToTarget = new HashSet<>();
    /** The loops a run can go round, by the node each is entered at (ADR-048 decision 1). */
    private final Map<String, Loop> loops = new LinkedHashMap<>();
    /** The connections back to a loop's entry: left out of the order and of what arrives there, and credited instead. */
    private final Set<String> back = new HashSet<>();
    /** The nodes a loop's entry leads to, the entries included: what may run again in a later round of the forward check. */
    private final Set<String> afterLoops = new HashSet<>();
    private final String targetNode;
    private final String targetAt;
    private final String targetUnit;

    FlowDemandPlanner(FlowDemand.Request request) {
        this.request = request;
        if (request == null || request.graph() == null || request.target() == null) {
            throw new Refusal(null, "A plan needs a graph and a target.");
        }
        index(request.graph());
        loops();
        sort();
        dominators();
        FlowDemand.Target target = request.target();
        if (target.portId() != null) {
            Port port = ports.get(target.portId());
            if (port == null || !"output".equals(direction(port))) {
                throw new Refusal(target.portId(), "The target must be an output port of the graph.");
            }
            targetNode = port.nodeId();
            targetAt = port.id();
            targetUnit = target.unit() != null ? target.unit() : port.unit();
        } else if (target.connectionId() != null) {
            Connection connection = connections.get(target.connectionId());
            if (connection == null) {
                throw new Refusal(target.connectionId(), "The target connection is not in the graph.");
            }
            targetNode = connection.from();
            targetAt = connection.id();
            targetUnit = target.unit() != null ? target.unit() : arrivalUnit(connection);
        } else {
            throw new Refusal(null, "The target needs a port or a connection.");
        }
        if (target.quantity() == null || target.quantity().signum() <= 0) {
            throw new Refusal(targetAt, "The target quantity must be more than 0.");
        }
        for (Loop loop : loops.values()) {
            boolean on = loop.members().contains(targetNode)
                && (target.connectionId() == null || loop.members().contains(connections.get(target.connectionId()).to()));
            if (on) throw new Refusal(targetAt, "The target is on the cycle through " + loop.entry() + "; aim at what leaves it.");
        }
        ancestors(targetNode, leadsToTarget);
        choose();
    }

    Result plan() {
        BigDecimal wanted = request.target().quantity();
        BigDecimal planned = wanted;
        List<Assumption> raised = new ArrayList<>();
        for (int attempt = 0; ; attempt++) {
            Pass pass = new Pass(wanted, planned);
            Map<String, Flow> supply = new HashMap<>();
            List<Requirement> requirements = pass.requirements(supply);
            if (!pass.limits.isEmpty()) {
                return pass.result(wanted, requirements, raised);
            }
            BigDecimal delivered = deliver(supply);
            if (delivered.compareTo(wanted) >= 0) {
                return pass.result(wanted, requirements, raised);
            }
            BigDecimal shortfall = wanted.subtract(delivered);
            if (shortfall.compareTo(wanted.multiply(ROUNDING_SHORTFALL).add(new BigDecimal("0.01"))) > 0) {
                // More than rounding can take: a rule this plan and the run do not share. Raising would hide it.
                pass.limits.add(new Limit(targetAt, "forward-check", "Running the plan forward brings " + plain(half(delivered))
                    + " of " + plain(wanted) + "."));
                return pass.result(wanted, requirements, raised);
            }
            if (attempt == RAISES) {
                pass.limits.add(new Limit(targetAt, "rounding", "Rounding on the way leaves " + plain(half(delivered))
                    + " of " + plain(wanted) + " after " + attempt + " raises."));
                return pass.result(wanted, requirements, raised);
            }
            planned = planned.multiply(wanted, MC).divide(delivered, MC).add(new BigDecimal("0.0001"));
            raised.clear();
            raised.add(new Assumption(targetAt, "raised", "planned for " + plain(up(planned)) + " so that " + plain(wanted)
                + " arrives after rounding"));
        }
    }

    // ---------------------------------------------------------------- the graph

    private void index(FlowDemand.Graph graph) {
        for (Node node : list(graph.nodes())) {
            if (node.id() == null || nodes.putIfAbsent(node.id(), node) != null) {
                throw new Refusal(node.id(), "Node ids must be present and distinct.");
            }
        }
        for (Port port : list(graph.ports())) {
            if (port.id() == null || !nodes.containsKey(port.nodeId()) || ports.putIfAbsent(port.id(), port) != null) {
                throw new Refusal(port.id(), "Port " + port.id() + " must have a distinct id and a node of the graph.");
            }
            String direction = direction(port);
            if (!"input".equals(direction) && !"output".equals(direction)) {
                throw new Refusal(port.id(), "Port " + port.id() + " must be an input or an output.");
            }
            portPosition.put(port.id(), portPosition.size());
            ("input".equals(direction) ? inputs : outputs).computeIfAbsent(port.nodeId(), ignored -> new ArrayList<>()).add(port);
        }
        for (Connection connection : list(graph.connections())) {
            if (connection.id() == null || !nodes.containsKey(connection.from()) || !nodes.containsKey(connection.to())
                || connections.putIfAbsent(connection.id(), connection) != null) {
                throw new Refusal(connection.id(), "Connection " + connection.id() + " must have a distinct id and join two nodes of the graph.");
            }
            requirePort(connection, connection.fromPort(), connection.from(), "output");
            requirePort(connection, connection.toPort(), connection.to(), "input");
            BigDecimal loss = connection.lossRate();
            if (loss != null && (loss.signum() < 0 || loss.compareTo(BigDecimal.ONE) >= 0)) {
                throw new Refusal(connection.id(), "Connection " + connection.id() + " has a loss rate outside [0, 1).");
            }
            drawn.put(connection.id(), drawn.size());
            outgoing.computeIfAbsent(connection.from(), ignored -> new ArrayList<>()).add(connection);
            incoming.computeIfAbsent(connection.to(), ignored -> new ArrayList<>()).add(connection);
            if (connection.toPort() != null) intoPort.computeIfAbsent(connection.toPort(), ignored -> new ArrayList<>()).add(connection);
            if (connection.fromPort() != null) fromPort.computeIfAbsent(connection.fromPort(), ignored -> new ArrayList<>()).add(connection);
        }
    }

    private void requirePort(Connection connection, String portId, String nodeId, String direction) {
        if (portId == null) return;
        Port port = ports.get(portId);
        if (port == null || !nodeId.equals(port.nodeId()) || !direction.equals(direction(port))) {
            throw new Refusal(connection.id(), "Connection " + connection.id() + " names a port that is not an " + direction
                + " of " + nodeId + ".");
        }
    }

    /** Topological order, in the order given where it leaves a choice. */
    private void sort() {
        Map<String, Integer> remaining = new HashMap<>();
        for (String id : nodes.keySet()) remaining.put(id, incoming.getOrDefault(id, List.of()).size());
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (String id : nodes.keySet()) if (remaining.get(id) == 0) ready.add(id);
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            position.put(id, order.size());
            order.add(id);
            for (Connection connection : outgoing.getOrDefault(id, List.of())) {
                if (back.contains(connection.id())) continue;
                if (remaining.merge(connection.to(), -1, Integer::sum) == 0) ready.add(connection.to());
            }
        }
        if (order.size() != nodes.size()) {
            String inCycle = nodes.keySet().stream().filter(id -> !position.containsKey(id)).findFirst().orElse(null);
            throw new Refusal(inCycle, "A graph with a cycle cannot be planned yet.");
        }
    }

    /** Each node's immediate dominator: the last node every path to it passes; {@link #VIRTUAL} when its paths start apart. */
    private void dominators() {
        depth.put(VIRTUAL, 0);
        for (String id : order) {
            String dominator = null;
            for (Connection connection : incoming.getOrDefault(id, List.of())) {
                dominator = dominator == null ? connection.from() : common(dominator, connection.from());
            }
            if (dominator == null) dominator = VIRTUAL;
            idom.put(id, dominator);
            depth.put(id, depth.get(dominator) + 1);
        }
    }

    private String common(String a, String b) {
        while (!a.equals(b)) {
            if (depth.get(a) >= depth.get(b)) a = idom.get(a);
            else b = idom.get(b);
        }
        return a;
    }

    private void ancestors(String id, Set<String> into) {
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(id));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!into.add(next)) continue;
            for (Connection connection : incoming.getOrDefault(next, List.of())) queue.add(connection.from());
        }
    }

    /** Each Exclusive's connection: the one given, else the first by rank (default last) that leads to the target. */
    private void choose() {
        for (Map.Entry<String, String> given : request.exclusiveChoices().entrySet()) {
            Connection connection = connections.get(given.getValue());
            if (!nodes.containsKey(given.getKey()) || !kind(given.getKey()).equals("exclusive") || connection == null || !connection.from().equals(given.getKey())) {
                throw new Refusal(given.getKey(), "The choice for " + given.getKey() + " must be one of its connections.");
            }
        }
        for (String id : order) {
            if (!kind(id).equals("exclusive")) continue;
            String given = request.exclusiveChoices().get(id);
            if (given != null) {
                choices.put(id, given);
                continue;
            }
            List<Connection> ranked = new ArrayList<>(outgoing.getOrDefault(id, List.of()));
            ranked.sort(Comparator.comparing(Connection::defaultRoute).thenComparingInt(Connection::priority)
                .thenComparingInt(connection -> drawn.get(connection.id())));
            ranked.stream().filter(connection -> connection.id().equals(targetAt) || leadsToTarget.contains(connection.to()))
                .findFirst().or(() -> ranked.stream().findFirst()).ifPresent(connection -> choices.put(id, connection.id()));
        }
    }

    private String kind(String nodeId) {
        String kind = nodes.get(nodeId).kind();
        return kind == null ? "process" : kind.toLowerCase(Locale.ROOT);
    }

    /** A node that runs in batches: not an Exclusive or a Split, with a port that has a quantity. */
    private boolean ratio(String nodeId) {
        String kind = kind(nodeId);
        if (kind.equals("exclusive") || kind.equals("split")) return false;
        for (Port port : ports(nodeId)) if (quantity(port) != null) return true;
        return false;
    }

    private List<Port> ports(String nodeId) {
        List<Port> all = new ArrayList<>(inputs.getOrDefault(nodeId, List.of()));
        all.addAll(outputs.getOrDefault(nodeId, List.of()));
        return all;
    }

    private static BigDecimal quantity(Port port) {
        return port.quantity() != null && port.quantity().signum() > 0 ? port.quantity() : null;
    }

    private static String direction(Port port) {
        return port.direction() == null ? null : port.direction().toLowerCase(Locale.ROOT);
    }

    private String arrivalUnit(Connection connection) {
        if (connection.toPort() != null) return ports.get(connection.toPort()).unit();
        return connection.fromPort() != null ? ports.get(connection.fromPort()).unit() : null;
    }

    private Port firstOutput(String nodeId) {
        return outputs.getOrDefault(nodeId, List.of()).stream().filter(port -> quantity(port) != null).findFirst().orElse(null);
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values;
    }

    // ---------------------------------------------------------------- loops (ADR-048)

    /** A loop: its nodes, the one it is entered at, and the connections that come back to that one. */
    private record Loop(String entry, Set<String> members, List<String> back) {
    }

    /**
     * The cycles a plan goes round (ADR-048 decision 1): each has an Exclusive or Split that leads out of it and no Join, as a run
     * needs (ADR-045), is entered at one node that passes what arrives, and every way round passes that node. The connections
     * back to it are left out of the order and of what arrives there; a pass credits what comes back by them instead.
     */
    private void loops() {
        for (Set<String> members : cycles()) {
            String first = members.iterator().next();
            for (String id : members) {
                if (kind(id).equals("join")) {
                    throw new Refusal(id, "Join " + id + " is on a cycle; a run passes a Join once, so the cycle cannot be planned.");
                }
            }
            boolean wayOut = members.stream().anyMatch(id -> (kind(id).equals("exclusive") || kind(id).equals("split"))
                && outgoing.getOrDefault(id, List.of()).stream().anyMatch(connection -> !members.contains(connection.to())));
            if (!wayOut) {
                throw new Refusal(first, "A cycle with no Exclusive or Split that leads out of it cannot be planned.");
            }
            List<String> entries = members.stream().filter(id -> incoming.getOrDefault(id, List.of()).stream()
                .anyMatch(connection -> !members.contains(connection.from()))).toList();
            if (entries.size() != 1) {
                throw new Refusal(first, entries.isEmpty()
                    ? "Nothing comes into the cycle through " + first + "; a plan needs a start before it."
                    : "The cycle through " + first + " is entered at " + String.join(", ", entries) + "; a plan needs one way in.");
            }
            String entry = entries.get(0);
            if (ratio(entry)) {
                throw new Refusal(entry, "The cycle comes back into " + entry + ", which works in batches; bring it back into a step"
                    + " that passes what arrives.");
            }
            if (!inOrder(members, entry)) {
                throw new Refusal(entry, "The cycle through " + entry + " has another cycle inside it; plan one at a time.");
            }
            List<String> backs = incoming.getOrDefault(entry, List.of()).stream()
                .filter(connection -> members.contains(connection.from())).map(Connection::id).toList();
            loops.put(entry, new Loop(entry, members, backs));
            back.addAll(backs);
        }
        for (String id : back) {
            Connection connection = connections.get(id);
            incoming.get(connection.to()).remove(connection);
            if (connection.toPort() != null) intoPort.get(connection.toPort()).remove(connection);
        }
        ArrayDeque<String> queue = new ArrayDeque<>(loops.keySet());
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!afterLoops.add(next)) continue;
            for (Connection connection : outgoing.getOrDefault(next, List.of())) {
                if (!back.contains(connection.id())) queue.add(connection.to());
            }
        }
    }

    /** The graph's strongly connected parts with more than one node or a connection to itself, each in node order. */
    private List<Set<String>> cycles() {
        List<String> finished = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String start : nodes.keySet()) {
            if (!seen.add(start)) continue;
            ArrayDeque<String> path = new ArrayDeque<>(List.of(start));
            ArrayDeque<Iterator<Connection>> next = new ArrayDeque<>();
            next.push(outgoing.getOrDefault(start, List.of()).iterator());
            while (!path.isEmpty()) {
                Iterator<Connection> out = next.peek();
                if (out.hasNext()) {
                    String to = out.next().to();
                    if (seen.add(to)) {
                        path.push(to);
                        next.push(outgoing.getOrDefault(to, List.of()).iterator());
                    }
                } else {
                    next.pop();
                    finished.add(path.pop());
                }
            }
        }
        Set<String> placed = new HashSet<>();
        List<Set<String>> cycles = new ArrayList<>();
        for (int index = finished.size() - 1; index >= 0; index--) {
            String root = finished.get(index);
            if (!placed.add(root)) continue;
            Set<String> part = new HashSet<>();
            ArrayDeque<String> queue = new ArrayDeque<>(List.of(root));
            while (!queue.isEmpty()) {
                String id = queue.removeFirst();
                part.add(id);
                for (Connection connection : incoming.getOrDefault(id, List.of())) {
                    if (placed.add(connection.from())) queue.add(connection.from());
                }
            }
            boolean cycle = part.size() > 1 || outgoing.getOrDefault(root, List.of()).stream()
                .anyMatch(connection -> connection.to().equals(root));
            if (!cycle) continue;
            Set<String> ordered = new LinkedHashSet<>();
            for (String id : nodes.keySet()) if (part.contains(id)) ordered.add(id);
            cycles.add(ordered);
        }
        List<String> drawnOrder = new ArrayList<>(nodes.keySet());
        cycles.sort(Comparator.comparingInt(cycle -> drawnOrder.indexOf(cycle.iterator().next())));
        return cycles;
    }

    /** Whether the cycle's nodes but {@code entry} have an order, so that every way round passes {@code entry}. */
    private boolean inOrder(Set<String> members, String entry) {
        Map<String, Integer> remaining = new HashMap<>();
        for (String id : members) if (!id.equals(entry)) remaining.put(id, 0);
        for (String id : remaining.keySet()) {
            for (Connection connection : outgoing.getOrDefault(id, List.of())) {
                if (remaining.containsKey(connection.to())) remaining.merge(connection.to(), 1, Integer::sum);
            }
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        remaining.forEach((id, count) -> { if (count == 0) ready.add(id); });
        int ordered = 0;
        while (!ready.isEmpty()) {
            String id = ready.removeFirst();
            ordered++;
            for (Connection connection : outgoing.getOrDefault(id, List.of())) {
                if (remaining.containsKey(connection.to()) && remaining.merge(connection.to(), -1, Integer::sum) == 0) {
                    ready.add(connection.to());
                }
            }
        }
        return ordered == remaining.size();
    }

    // ---------------------------------------------------------------- amounts

    /** An amount and how it came to be: {@code rule} at {@code at} made it from {@code previous}. */
    private record Need(BigDecimal value, String unit, Need previous, String at, String rule) {
    }

    private record Flow(BigDecimal value, String unit) {
    }

    private static Need step(Need from, String at, String rule, BigDecimal value, String unit) {
        return new Need(value, unit, from, at, rule);
    }

    private BigDecimal convert(BigDecimal value, String from, String to, String at) {
        if (from == null || to == null || from.equals(to)) return value;
        return request.units().convert(value, from, to)
            .orElseThrow(() -> new Refusal(at, "Cannot convert " + from + " to " + to + " at " + at + "."));
    }

    /** The need in {@code unit}: a {@code unit} step when it converts, the same need when either unit is unknown. */
    private Need inUnit(Need need, String unit, String at) {
        if (unit == null || unit.equals(need.unit())) return need;
        if (need.unit() == null) return new Need(need.value(), unit, need.previous(), need.at(), need.rule());
        return step(need, at, "unit", convert(need.value(), need.unit(), unit, at), unit);
    }

    /** The larger of two needs for the same thing, compared in the first's unit; the first wins a tie. */
    private Need larger(Need a, Need b, String at) {
        if (a == null) return b;
        if (b == null) return a;
        BigDecimal other = BATCH.equals(a.unit()) || BATCH.equals(b.unit()) ? b.value() : convert(b.value(), b.unit(), a.unit(), at);
        return other.compareTo(a.value()) > 0 ? b : a;
    }

    private static BigDecimal up(BigDecimal value) {
        // Strips what DECIMAL128 division leaves far past the 4th decimal before rounding up.
        return value.setScale(20, RoundingMode.HALF_UP).setScale(4, RoundingMode.CEILING);
    }

    private static BigDecimal half(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    // ---------------------------------------------------------------- the backward pass

    private record Start(String at, String nodeId, Port port, Need need) {
    }

    private record Region(String from, String join, Set<String> inside) {
    }

    /** One backward pass for a planned target: needs from the target back to the starts. */
    private final class Pass {
        private final Map<String, Need> send = new HashMap<>();
        private final Map<String, Need> portNeed = new HashMap<>();
        private final Map<String, Need> nodeOut = new HashMap<>();
        private final Map<String, Need> seed = new HashMap<>();
        private final Map<String, Need> batches = new HashMap<>();
        private final Map<String, Need> through = new HashMap<>();
        private final Set<String> usedPorts = new HashSet<>();
        private final Set<String> usedConnections = new HashSet<>();
        private final List<Start> starts = new ArrayList<>();
        private final List<Region> regions = new ArrayList<>();
        private final Set<String> interior = new HashSet<>();
        private final List<Limit> limits = new ArrayList<>();
        private final Map<String, Assumption> assumptions = new LinkedHashMap<>();

        Pass(BigDecimal wanted, BigDecimal planned) {
            Need root = new Need(wanted, targetUnit, null, targetAt, "target");
            if (planned.compareTo(wanted) != 0) root = step(root, targetAt, "raised", planned, targetUnit);
            FlowDemand.Target target = request.target();
            if (target.portId() != null) {
                Port port = ports.get(target.portId());
                Need need = inUnit(root, port.unit(), port.id());
                if (ratio(port.nodeId())) portNeed.merge(port.id(), need, (a, b) -> larger(a, b, port.id()));
                else nodeOut.merge(port.nodeId(), need, (a, b) -> larger(a, b, port.id()));
            } else {
                needArrival(connections.get(target.connectionId()), root);
            }
            List<String> reverse = new ArrayList<>(order);
            Collections.reverse(reverse);
            for (String id : reverse) {
                if (!leadsToTarget.contains(id) || interior.contains(id)) continue;
                String kind = kind(id);
                if (ratio(id)) visitRatio(id);
                else if (kind.equals("split")) visitSplit(id);
                else if (kind.equals("exclusive")) visitExclusive(id);
                else visitPass(id);
            }
            for (Region region : regions) fill(region);
        }

        private void visitRatio(String id) {
            Need k = seed.get(id);
            for (Port output : outputs.getOrDefault(id, List.of())) {
                Need need = portNeed.get(output.id());
                if (need == null) continue;
                BigDecimal q = quantity(output);
                if (q == null) throw new Refusal(output.id(), "Port " + output.id() + " is needed but has no quantity.");
                requireNoFormula(output);
                Need inPort = inUnit(need, output.unit(), output.id());
                k = larger(k, step(inPort, output.id(), "ratio", inPort.value().divide(q, MC), BATCH), id);
            }
            if (k == null) return;
            batches.put(id, k);
            List<Port> measured = inputs.getOrDefault(id, List.of()).stream().filter(port -> quantity(port) != null).toList();
            requireMeasuredInputs(id);
            if (measured.isEmpty()) {
                Port first = firstOutput(id);
                starts.add(new Start(id, id, first, step(k, id, "ratio", k.value().multiply(quantity(first), MC), first.unit())));
                return;
            }
            for (Port input : measured) {
                requireNoFormula(input);
                Need need = step(k, input.id(), "ratio", k.value().multiply(quantity(input), MC), input.unit());
                List<Connection> into = intoPort.getOrDefault(input.id(), List.of());
                if (into.isEmpty()) {
                    starts.add(new Start(input.id(), id, input, need));
                } else if (into.size() > 1) {
                    throw new Refusal(input.id(), "Port " + input.id() + " has several connections in; gather them with a Join first.");
                } else {
                    needArrival(into.get(0), need);
                }
            }
        }

        /** Every connection into a node that runs in batches reaches an input port with a quantity. */
        private void requireMeasuredInputs(String id) {
            for (Connection connection : incoming.getOrDefault(id, List.of())) {
                if (connection.toPort() == null) {
                    throw new Refusal(connection.id(), "Connection " + connection.id() + " reaches " + id
                        + " without an input port; connect it to one.");
                }
                if (quantity(ports.get(connection.toPort())) == null) {
                    throw new Refusal(connection.toPort(), "Port " + connection.toPort()
                        + " is connected but has no quantity; give it one so the plan knows how much it needs.");
                }
            }
        }

        private void requireNoFormula(Port port) {
            if (port.formula() != null && !port.formula().isBlank()) {
                throw new Refusal(port.id(), "Port " + port.id() + " has a formula; plans use fixed quantities for now.");
            }
        }

        private void visitSplit(String id) {
            List<Connection> outs = outgoing.getOrDefault(id, List.of());
            Map<String, BigDecimal[]> affine = affine(id, outs);
            boolean onlyFixed = outs.stream().noneMatch(connection -> "share".equals(connection.splitKind())
                || "rest".equals(connection.splitKind()));
            BigDecimal least = BigDecimal.ZERO;
            Need binding = null;
            String bindingAt = null;
            String bindingRule = null;
            BigDecimal bindingValue = null;
            for (Connection connection : outs) {
                BigDecimal[] line = affine.get(connection.id());
                if (line[0].signum() > 0 && line[1].signum() < 0) {
                    least = least.max(line[1].negate().divide(line[0], MC));
                }
                Need need = send.get(connection.id());
                if (need == null) continue;
                if (line[0].signum() == 0) {
                    if (line[1].compareTo(need.value()) < 0) {
                        limits.add(new Limit(connection.id(), "split-fixed", "Split " + id + " gives " + connection.id()
                            + " a fixed " + plain(line[1]) + " but it needs " + plain(up(need.value())) + "."));
                    }
                    if (binding == null) {
                        binding = need;
                        bindingAt = connection.id();
                        bindingRule = "split-fixed";
                        bindingValue = BigDecimal.ZERO;
                    }
                    continue;
                }
                BigDecimal value = need.value().subtract(line[1]).divide(line[0], MC);
                if (bindingValue == null || value.compareTo(bindingValue) > 0) {
                    binding = need;
                    bindingAt = connection.id();
                    bindingRule = "split-" + connection.splitKind();
                    bindingValue = value;
                }
            }
            Need seeded = seed.get(id);
            if (binding == null && seeded == null) return;
            Need x;
            if (binding == null) {
                x = seeded;
            } else {
                BigDecimal value = onlyFixed ? fixedTotal(outs) : bindingValue.max(least);
                x = step(binding, bindingAt, bindingRule, value, binding.unit());
                x = larger(x, seeded, id);
            }
            through.put(id, x);
            List<Connection> ins = incoming.getOrDefault(id, List.of());
            if (ins.size() > 1) {
                throw new Refusal(id, "Split " + id + " has several connections in; a plan needs one.");
            }
            passIn(id, x);
        }

        private void visitExclusive(String id) {
            String chosen = choices.get(id);
            Need need = larger(nodeOut.get(id), seed.get(id), id);
            for (Connection connection : outgoing.getOrDefault(id, List.of())) {
                Need wanted = send.get(connection.id());
                if (wanted == null) continue;
                if (connection.id().equals(chosen)) {
                    need = larger(wanted, need, id);
                } else if (wanted.value().signum() > 0) {
                    limits.add(new Limit(connection.id(), "exclusive", "Exclusive " + id + " takes " + chosen + ", so "
                        + connection.id() + " gets none of the " + plain(up(wanted.value())) + " it needs."));
                }
            }
            if (need == null) return;
            boolean given = request.exclusiveChoices().containsKey(id);
            assumptions.putIfAbsent(id + "/exclusive", new Assumption(id, "exclusive",
                chosen + (given ? " (given)" : " (leads to the target)")));
            Need x = step(need, id, "exclusive", need.value(), need.unit());
            through.put(id, x);
            passIn(id, x);
        }

        private void visitPass(String id) {
            Need need = larger(nodeOut.get(id), seed.get(id), id);
            for (Connection connection : outgoing.getOrDefault(id, List.of())) need = larger(need, send.get(connection.id()), id);
            for (Port output : outputs.getOrDefault(id, List.of())) need = larger(need, portNeed.get(output.id()), id);
            if (need == null) return;
            Need x = step(need, id, "pass", need.value(), need.unit());
            through.put(id, x);
            passIn(id, x);
        }

        /** What a node that passes what arrives needs from before it. */
        private void passIn(String id, Need x) {
            x = credited(id, x);
            List<Connection> ins = incoming.getOrDefault(id, List.of());
            if (ins.isEmpty()) {
                List<Port> outs = outputs.getOrDefault(id, List.of());
                starts.add(new Start(id, id, outs.size() == 1 ? outs.get(0) : null, x));
            } else if (ins.size() == 1) {
                needArrival(ins.get(0), x);
            } else {
                gather(id, x);
            }
        }

        /**
         * What must come into a loop's entry from before it for {@code x} to pass it over the rounds a run may go (ADR-048
         * decision 2): if a share g of what passes comes back after a round, x(1 &minus; g) / (1 &minus; g<sup>n</sup>). What the
         * way back passes is filled in from x, so its steps and materials are planned too.
         */
        private Need credited(String id, Need x) {
            Loop loop = loops.get(id);
            if (loop == null || x.value().signum() == 0) return x;
            Set<String> inside = new LinkedHashSet<>(loop.members());
            inside.remove(id);
            Region region = new Region(id, id, inside);
            Forward forward = new Forward(false, true, Map.of());
            forward.seed(id, x.value(), x.unit(), region);
            for (String node : order) if (inside.contains(node)) forward.evaluate(node, region);
            BigDecimal returned = BigDecimal.ZERO;
            for (String connection : loop.back()) {
                Flow flow = forward.arrived.get(connection);
                if (flow != null) returned = returned.add(convert(flow.value(), flow.unit(), x.unit(), connection));
            }
            for (String member : loop.members()) {
                for (Connection connection : outgoing.getOrDefault(member, List.of())) {
                    if (!loop.members().contains(connection.to())) continue;
                    usedConnections.add(connection.id());
                    if (connection.fromPort() != null) usedPorts.add(connection.fromPort());
                }
            }
            String by = String.join(", ", loop.back());
            BigDecimal share = returned.divide(x.value(), MC);
            if (share.signum() == 0) {
                assumptions.putIfAbsent(id + "/loop", new Assumption(id, "loop", "nothing comes back by " + by
                    + ": all of it is planned to go on the first time"));
                return x;
            }
            if (share.compareTo(BigDecimal.ONE) >= 0) {
                throw new Refusal(id, "All that passes " + id + " comes back by " + by + "; a plan needs a way out that takes some.");
            }
            for (String node : order) {
                if (!inside.contains(node) || batches.containsKey(node) || through.containsKey(node)) continue;
                if (ratio(node)) {
                    BigDecimal k = forward.batches.get(node);
                    if (k == null || k.signum() == 0) continue;
                    Need batch = step(x, node, "loop", k, BATCH);
                    batches.put(node, batch);
                    for (Port input : inputs.getOrDefault(node, List.of())) {
                        if (quantity(input) == null || !intoPort.getOrDefault(input.id(), List.of()).isEmpty()) continue;
                        starts.add(new Start(input.id(), node, input,
                            step(batch, input.id(), "ratio", k.multiply(quantity(input), MC), input.unit())));
                    }
                } else if (forward.through.containsKey(node)) {
                    Flow flow = forward.through.get(node);
                    through.put(node, step(x, node, "loop", flow.value(), flow.unit()));
                }
            }
            int rounds = FlowShares.LOOP_PASSES;
            assumptions.putIfAbsent(id + "/loop", new Assumption(id, "loop", plain(half(share.multiply(HUNDRED, MC)))
                + "% of what passes comes back by " + by + ", for up to " + rounds + " rounds"));
            BigDecimal fresh = x.value().multiply(BigDecimal.ONE.subtract(share), MC)
                .divide(BigDecimal.ONE.subtract(share.pow(rounds, MC)), MC);
            return step(x, id, "loop", fresh, x.unit());
        }

        /** What must be sent along a connection for {@code arrival} to arrive. */
        private void needArrival(Connection connection, Need arrival) {
            Need need = inUnit(arrival, arrivalUnit(connection), connection.id());
            BigDecimal loss = connection.lossRate();
            if (loss != null && loss.signum() > 0) {
                need = step(need, connection.id(), "loss", need.value().divide(BigDecimal.ONE.subtract(loss), MC), need.unit());
            }
            if (connection.fromPort() != null) need = inUnit(need, ports.get(connection.fromPort()).unit(), connection.id());
            if (connection.capacity() != null && need.value().compareTo(connection.capacity()) > 0) {
                limits.add(new Limit(connection.id(), "capacity", "Connection " + connection.id() + " must carry "
                    + plain(up(need.value())) + " but holds " + plain(connection.capacity()) + "."));
            }
            if (connection.condition() != null && !connection.condition().isBlank() && !kind(connection.from()).equals("exclusive")) {
                assumptions.putIfAbsent(connection.id() + "/condition", new Assumption(connection.id(), "condition", "holds"));
            }
            send.merge(connection.id(), need, (a, b) -> larger(a, b, connection.id()));
            if (ratio(connection.from())) {
                if (connection.fromPort() == null) {
                    throw new Refusal(connection.id(), "Connection " + connection.id() + " leaves " + connection.from()
                        + " without an output port; connect it from one.");
                }
                portNeed.merge(connection.fromPort(), need, (a, b) -> larger(a, b, connection.id()));
            }
        }

        /**
         * A node several connections arrive at: what each brings follows from the node all of them leave from. The least amount
         * there that brings {@code x} becomes that node's need.
         */
        private void gather(String join, Need x) {
            String from = idom.get(join);
            if (VIRTUAL.equals(from)) {
                throw new Refusal(join, join + " gathers branches that start apart, so nothing says how much each brings. "
                    + "Give its ports quantities, or plan the branches one at a time.");
            }
            Set<String> inside = between(from, join);
            for (String id : inside) {
                if (id.equals(targetNode)) {
                    throw new Refusal(id, "The target is inside the branches " + join + " gathers.");
                }
                if (ratio(id)) requireMeasuredInputs(id);
                if (kind(id).equals("split") && incoming.getOrDefault(id, List.of()).size() > 1) {
                    throw new Refusal(id, "Split " + id + " has several connections in; a plan needs one.");
                }
                for (Connection connection : outgoing.getOrDefault(id, List.of())) {
                    String next = connection.to();
                    if (!inside.contains(next) && !next.equals(join) && leadsToTarget.contains(next)) {
                        throw new Refusal(connection.id(), "Connection " + connection.id() + " leaves the branches " + join
                            + " gathers and reaches the target another way.");
                    }
                }
            }
            BigDecimal amount = solve(from, inside, join, x);
            Need need = step(x, join, "join", amount, ratio(from) ? BATCH : x.unit());
            seed.merge(from, need, (a, b) -> larger(a, b, join));
            regions.add(new Region(from, join, inside));
            interior.addAll(inside);
        }

        /** Results inside a gathered region, from the node all its branches leave from. */
        private void fill(Region region) {
            Need from = ratio(region.from()) ? batches.get(region.from()) : through.get(region.from());
            if (from == null) return;
            Forward forward = new Forward(false, true, Map.of());
            forward.seed(region.from(), from.value(), from.unit(), region);
            for (String id : order) {
                if (!region.inside().contains(id)) continue;
                forward.evaluate(id, region);
                if (ratio(id)) {
                    Need k = step(from, id, "join", forward.batches.get(id), BATCH);
                    batches.put(id, k);
                    for (Port input : inputs.getOrDefault(id, List.of())) {
                        if (quantity(input) == null || !intoPort.getOrDefault(input.id(), List.of()).isEmpty()) continue;
                        starts.add(new Start(input.id(), id, input,
                            step(k, input.id(), "ratio", k.value().multiply(quantity(input), MC), input.unit())));
                    }
                } else if (forward.through.containsKey(id)) {
                    Flow flow = forward.through.get(id);
                    through.put(id, step(from, id, "join", flow.value(), flow.unit()));
                }
            }
            for (String id : region.inside()) {
                for (Connection connection : outgoing.getOrDefault(id, List.of())) markUsed(connection, region);
            }
            for (Connection connection : outgoing.getOrDefault(region.from(), List.of())) markUsed(connection, region);
        }

        private void markUsed(Connection connection, Region region) {
            if (!region.inside().contains(connection.to()) && !connection.to().equals(region.join())) return;
            usedConnections.add(connection.id());
            if (connection.fromPort() != null) usedPorts.add(connection.fromPort());
        }

        // ------------------------------------------------------------ results

        /** The starts' needs rounded up, also put in {@code supply} for the forward check. */
        List<Requirement> requirements(Map<String, Flow> supply) {
            List<Start> sorted = new ArrayList<>(starts);
            sorted.sort(Comparator.comparingInt((Start start) -> position.get(start.nodeId()))
                .thenComparingInt(start -> start.port() == null ? -1 : portPosition.get(start.port().id())));
            Map<String, Requirement> byAt = new LinkedHashMap<>();
            for (Start start : sorted) {
                BigDecimal rounded = up(start.need().value());
                List<Step> trace = trace(start.need());
                trace.add(new Step(start.at(), "round", half(start.need().value()), rounded,
                    start.need().value().signum() == 0 ? null : rounded.divide(start.need().value(), MC)));
                Port port = start.port();
                byAt.put(start.at(), new Requirement(start.at(), start.nodeId(), port == null ? null : port.resource(),
                    port == null ? null : port.ref(), rounded, start.need().unit(), trace));
                supply.put(start.at(), new Flow(rounded, start.need().unit()));
            }
            return List.copyOf(byAt.values());
        }

        private List<Step> trace(Need need) {
            List<Step> steps = new ArrayList<>();
            for (Need at = need; at.previous() != null; at = at.previous()) {
                BigDecimal before = at.previous().value();
                steps.add(new Step(at.at(), at.rule(), half(before), half(at.value()),
                    before.signum() == 0 ? null : at.value().divide(before, MC)));
            }
            Collections.reverse(steps);
            return steps;
        }

        Result result(BigDecimal wanted, List<Requirement> requirements, List<Assumption> raised) {
            List<NodeDemand> demands = new ArrayList<>();
            List<Surplus> surplus = new ArrayList<>();
            for (String id : order) {
                Need k = batches.get(id);
                Need x = through.get(id);
                if (k == null && x == null) continue;
                List<PortDemand> portDemands = new ArrayList<>();
                if (k != null) {
                    for (Port input : inputs.getOrDefault(id, List.of())) {
                        if (quantity(input) == null) continue;
                        portDemands.add(new PortDemand(input.id(), "input", up(k.value().multiply(quantity(input), MC)), input.unit()));
                    }
                    for (Port output : outputs.getOrDefault(id, List.of())) {
                        if (quantity(output) == null) continue;
                        BigDecimal made = k.value().multiply(quantity(output), MC);
                        portDemands.add(new PortDemand(output.id(), "output", half(made), output.unit()));
                        surplus(output, made, surplus);
                    }
                }
                if (kind(id).equals("split") && x != null) splitLeft(id, x, surplus);
                demands.add(new NodeDemand(id, k == null ? null : up(k.value()), x == null ? null : up(x.value()),
                    x == null ? null : x.unit(), portDemands, execution(id, k, x)));
            }
            List<Assumption> all = new ArrayList<>(raised);
            all.addAll(assumptions.values());
            return new Result(wanted, targetUnit, limits.isEmpty(), requirements, demands, surplus, List.copyOf(limits), all);
        }

        private void surplus(Port output, BigDecimal made, List<Surplus> surplus) {
            if (usedPorts.contains(output.id())) return;
            Need need = portNeed.get(output.id());
            BigDecimal left = need == null ? made : made.subtract(convert(need.value(), need.unit(), output.unit(), output.id()));
            if (half(left).signum() > 0) {
                surplus.add(new Surplus(output.id(), need == null ? "byproduct" : "over", half(left), output.unit()));
            }
        }

        private void splitLeft(String id, Need x, List<Surplus> surplus) {
            List<Connection> outs = outgoing.getOrDefault(id, List.of());
            Map<String, BigDecimal[]> affine = affine(id, outs);
            for (Connection connection : outs) {
                if (usedConnections.contains(connection.id())) continue;
                BigDecimal[] line = affine.get(connection.id());
                BigDecimal amount = line[0].multiply(x.value(), MC).add(line[1]);
                Need need = send.get(connection.id());
                BigDecimal left = need == null ? amount : amount.subtract(need.value());
                if (half(left).signum() > 0) surplus.add(new Surplus(connection.id(), "split-left", half(left), x.unit()));
            }
        }

        private List<ExecutionTotal> execution(String id, Need k, Need x) {
            List<ExecutionTotal> totals = new ArrayList<>();
            for (ExecutionRequirement requirement : nodes.get(id).execution()) {
                if (requirement.amount() == null) continue;
                boolean perOutput = "output_unit".equals(requirement.per());
                BigDecimal base;
                if (k != null) {
                    Port first = firstOutput(id);
                    base = perOutput && first != null ? k.value().multiply(quantity(first), MC) : k.value();
                } else {
                    base = perOutput ? x.value() : BigDecimal.ONE;
                }
                totals.add(new ExecutionTotal(requirement.kind(), requirement.ref(),
                    up(base.multiply(requirement.amount(), MC)), requirement.unit()));
            }
            return totals;
        }
    }

    /** The nodes reachable from {@code from} that reach {@code join}, neither included. */
    private Set<String> between(String from, String join) {
        Set<String> after = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            String next = queue.removeFirst();
            if (!after.add(next)) continue;
            for (Connection connection : outgoing.getOrDefault(next, List.of())) {
                if (!back.contains(connection.id())) queue.add(connection.to());
            }
        }
        Set<String> before = new HashSet<>();
        ancestors(join, before);
        Set<String> inside = new LinkedHashSet<>();
        for (String id : order) if (after.contains(id) && before.contains(id) && !id.equals(from) && !id.equals(join)) inside.add(id);
        return inside;
    }

    /**
     * Each connection out of a Split as {a, b}: it gets a &middot; X + b of an input X, unrounded (ADR-008): a percent X&middot;s,
     * a fixed f, the rest X(1 &minus; &Sigma;s) &minus; &Sigma;f; without a rest the last percent takes what is left.
     */
    private Map<String, BigDecimal[]> affine(String id, List<Connection> outs) {
        BigDecimal shares = BigDecimal.ZERO;
        BigDecimal fixed = BigDecimal.ZERO;
        String rest = null;
        String lastShare = null;
        for (Connection connection : outs) {
            String kind = connection.splitKind();
            if ("rest".equals(kind)) {
                rest = connection.id();
            } else if ("share".equals(kind) && connection.splitValue() != null) {
                shares = shares.add(connection.splitValue().divide(HUNDRED, MC));
                lastShare = connection.id();
            } else if ("fixed".equals(kind) && connection.splitValue() != null) {
                fixed = fixed.add(connection.splitValue());
            } else {
                throw new Refusal(connection.id(), "Split " + id + " connection " + connection.id() + " has no share.");
            }
        }
        BigDecimal left = BigDecimal.ONE.subtract(shares);
        if (left.signum() < 0) throw new Refusal(id, "Split " + id + " gives out more than 100%.");
        Map<String, BigDecimal[]> lines = new HashMap<>();
        for (Connection connection : outs) {
            String kind = connection.splitKind();
            if ("rest".equals(kind)) {
                lines.put(connection.id(), new BigDecimal[] {left, fixed.negate()});
            } else if ("fixed".equals(kind)) {
                lines.put(connection.id(), new BigDecimal[] {BigDecimal.ZERO, connection.splitValue()});
            } else {
                BigDecimal share = connection.splitValue().divide(HUNDRED, MC);
                boolean takesLeft = rest == null && connection.id().equals(lastShare);
                lines.put(connection.id(), takesLeft ? new BigDecimal[] {share.add(left), fixed.negate()}
                    : new BigDecimal[] {share, BigDecimal.ZERO});
            }
        }
        return lines;
    }

    private BigDecimal fixedTotal(List<Connection> outs) {
        return outs.stream().map(Connection::splitValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The least amount at {@code from} (batches, or what arrives there) that brings {@code x} to {@code join}. */
    private BigDecimal solve(String from, Set<String> inside, String join, Need x) {
        Region region = new Region(from, join, inside);
        Function<BigDecimal, BigDecimal> brings = amount -> {
            Forward forward = new Forward(false, true, Map.of());
            forward.seed(from, amount, x.unit(), region);
            for (String id : order) if (inside.contains(id)) forward.evaluate(id, region);
            BigDecimal total = BigDecimal.ZERO;
            for (Connection connection : incoming.getOrDefault(join, List.of())) {
                Flow flow = forward.arrived.get(connection.id());
                if (flow != null) total = total.add(convert(flow.value(), flow.unit(), x.unit(), connection.id()));
            }
            return total;
        };
        BigDecimal wanted = x.value();
        BigDecimal first = wanted.max(BigDecimal.ONE);
        BigDecimal second = first.multiply(BigDecimal.valueOf(2));
        BigDecimal atFirst = brings.apply(first);
        BigDecimal atSecond = brings.apply(second);
        if (atSecond.compareTo(atFirst) > 0) {
            // Exact when what arrives is a straight line in the amount, as it is without fixed shares or several limiting inputs.
            BigDecimal slope = atSecond.subtract(atFirst).divide(second.subtract(first), MC);
            BigDecimal guess = first.add(wanted.subtract(atFirst).divide(slope, MC), MC);
            for (BigDecimal candidate : List.of(guess, guess.multiply(new BigDecimal("1.000000000000000000000000000001"), MC))) {
                if (candidate.signum() >= 0 && brings.apply(candidate).compareTo(wanted) >= 0) return candidate;
            }
        }
        BigDecimal high = second;
        for (int doubling = 0; brings.apply(high).compareTo(wanted) < 0; doubling++) {
            if (doubling == 200) throw new Refusal(join, "The branches " + join + " gathers cannot bring " + plain(wanted) + ".");
            high = high.multiply(BigDecimal.valueOf(2));
        }
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal two = BigDecimal.valueOf(2);
        for (int halving = 0; halving < 200; halving++) {
            BigDecimal middle = low.add(high).divide(two, MC);
            if (brings.apply(middle).compareTo(wanted) >= 0) high = middle;
            else low = middle;
        }
        return high;
    }

    // ---------------------------------------------------------------- forward

    /**
     * What the starts' amounts deliver to the target, routed as a run routes them ({@link FlowShares}). With loops, round after
     * round: what comes back passes the entry again with what waits at input ports, up to {@link FlowShares#LOOP_PASSES} rounds
     * (ADR-048 decision 3).
     */
    private BigDecimal deliver(Map<String, Flow> supply) {
        Forward forward = new Forward(true, false, supply);
        for (String id : order) forward.evaluate(id, null);
        BigDecimal delivered = delivered(forward);
        for (int round = 2; round <= FlowShares.LOOP_PASSES && !loops.isEmpty(); round++) {
            Map<String, Flow> returned = forward.returns();
            if (returned.isEmpty()) break;
            forward = new Forward(true, false, Map.of(), returned, forward.held());
            for (String id : order) if (afterLoops.contains(id)) forward.evaluate(id, null);
            delivered = delivered.add(delivered(forward));
        }
        return delivered;
    }

    private BigDecimal delivered(Forward forward) {
        Flow at;
        FlowDemand.Target target = request.target();
        if (target.portId() != null) {
            Port port = ports.get(target.portId());
            BigDecimal k = forward.batches.get(port.nodeId());
            at = k != null ? new Flow(k.multiply(quantity(port), MC), port.unit()) : forward.through.get(port.nodeId());
        } else {
            at = forward.arrived.get(target.connectionId());
        }
        return at == null ? BigDecimal.ZERO : convert(at.value(), at.unit(), targetUnit, targetAt);
    }

    /**
     * Flow forward through nodes in order. {@code rounded} routes as a run does (4-decimal shares and losses); otherwise exact.
     * With {@code asNeeded}, an input port nothing connects to never limits a node.
     */
    private final class Forward {
        private final boolean rounded;
        private final boolean asNeeded;
        private final Map<String, Flow> supply;
        /** What came back to each loop's entry in the round before (ADR-048). */
        private final Map<String, Flow> returned;
        /** What waits at each input port from the round before: there but not used, in the port's unit. */
        private final Map<String, BigDecimal> held;
        private final Map<String, BigDecimal> offered = new HashMap<>();
        private final Map<String, Flow> arrived = new HashMap<>();
        private final Map<String, BigDecimal> batches = new HashMap<>();
        private final Map<String, Flow> through = new HashMap<>();

        Forward(boolean rounded, boolean asNeeded, Map<String, Flow> supply) {
            this(rounded, asNeeded, supply, Map.of(), Map.of());
        }

        Forward(boolean rounded, boolean asNeeded, Map<String, Flow> supply, Map<String, Flow> returned,
            Map<String, BigDecimal> held) {
            this.rounded = rounded;
            this.asNeeded = asNeeded;
            this.supply = supply;
            this.returned = returned;
            this.held = held;
        }

        /** What came back to each loop's entry this round. */
        Map<String, Flow> returns() {
            Map<String, Flow> back = new HashMap<>();
            for (Loop loop : loops.values()) {
                Flow total = null;
                for (String id : loop.back()) {
                    Flow flow = arrived.get(id);
                    if (flow == null || flow.value().signum() <= 0) continue;
                    total = total == null ? flow
                        : new Flow(total.value().add(convert(flow.value(), flow.unit(), total.unit(), id)), total.unit());
                }
                if (total != null) back.put(loop.entry(), total);
            }
            return back;
        }

        /** What is left at each input port after this round: what was there less what its node's batches used. */
        Map<String, BigDecimal> held() {
            Map<String, BigDecimal> left = new HashMap<>();
            offered.forEach((portId, amount) -> {
                Port port = ports.get(portId);
                BigDecimal k = batches.get(port.nodeId());
                BigDecimal rest = k == null ? amount : amount.subtract(k.multiply(quantity(port), MC));
                if (rest.signum() > 0) left.put(portId, rest);
            });
            return left;
        }

        /** {@code from}'s output for {@code amount} (batches, or what arrives), sent only into the region. */
        void seed(String from, BigDecimal amount, String unit, Region region) {
            if (ratio(from)) {
                batches.put(from, amount);
                emitBatches(from, amount, region);
            } else {
                Flow flow = new Flow(amount, unit);
                through.put(from, flow);
                emitPassed(from, flow, region);
            }
        }

        void evaluate(String id, Region region) {
            if (ratio(id)) {
                BigDecimal k = null;
                boolean measured = false;
                for (Port input : inputs.getOrDefault(id, List.of())) {
                    BigDecimal q = quantity(input);
                    if (q == null) continue;
                    measured = true;
                    List<Connection> into = intoPort.getOrDefault(input.id(), List.of());
                    BigDecimal available;
                    if (into.isEmpty()) {
                        if (asNeeded) continue;
                        Flow given = supply.get(input.id());
                        available = given == null ? BigDecimal.ZERO : convert(given.value(), given.unit(), input.unit(), input.id());
                    } else {
                        available = BigDecimal.ZERO;
                        for (Connection connection : into) {
                            Flow flow = arrived.get(connection.id());
                            if (flow != null) available = available.add(convert(flow.value(), flow.unit(), input.unit(), connection.id()));
                        }
                    }
                    available = available.add(held.getOrDefault(input.id(), BigDecimal.ZERO));
                    offered.put(input.id(), available);
                    BigDecimal ratio = available.divide(q, MC);
                    k = k == null ? ratio : k.min(ratio);
                }
                if (!measured) {
                    Flow given = supply.get(id);
                    Port first = firstOutput(id);
                    k = given == null || first == null ? BigDecimal.ZERO
                        : convert(given.value(), given.unit(), first.unit(), id).divide(quantity(first), MC);
                }
                if (k == null) k = BigDecimal.ZERO;
                batches.put(id, k);
                emitBatches(id, k, region);
                return;
            }
            List<Connection> ins = incoming.getOrDefault(id, List.of());
            Flow total = ins.isEmpty() ? supply.get(id) : null;
            for (Connection connection : ins) {
                Flow flow = arrived.get(connection.id());
                if (flow == null) continue;
                total = total == null ? flow
                    : new Flow(total.value().add(convert(flow.value(), flow.unit(), total.unit(), connection.id())), total.unit());
            }
            Flow back = returned.get(id);
            if (back != null) {
                total = total == null ? back
                    : new Flow(total.value().add(convert(back.value(), back.unit(), total.unit(), id)), total.unit());
            }
            if (total == null) return;
            through.put(id, total);
            emitPassed(id, total, region);
        }

        private void emitBatches(String id, BigDecimal k, Region region) {
            for (Port output : outputs.getOrDefault(id, List.of())) {
                BigDecimal q = quantity(output);
                if (q == null) continue;
                Flow made = new Flow(k.multiply(q, MC), output.unit());
                for (Connection connection : fromPort.getOrDefault(output.id(), List.of())) emit(connection, made, region);
            }
        }

        private void emitPassed(String id, Flow total, Region region) {
            List<Connection> outs = outgoing.getOrDefault(id, List.of());
            String kind = kind(id);
            if (kind.equals("split")) {
                Map<String, BigDecimal> amounts = new HashMap<>();
                if (rounded) {
                    amounts.putAll(FlowShares.split(total.value(), outs.stream()
                        .map(connection -> new FlowShares.Share(connection.id(), connection.splitKind(), connection.splitValue()))
                        .toList()).amounts());
                } else {
                    affine(id, outs).forEach((connection, line) -> amounts.put(connection,
                        line[0].multiply(total.value(), MC).add(line[1])));
                }
                for (Connection connection : outs) {
                    BigDecimal amount = amounts.get(connection.id());
                    if (amount != null && amount.signum() > 0) emit(connection, new Flow(amount, total.unit()), region);
                }
            } else if (kind.equals("exclusive")) {
                for (Connection connection : outs) if (connection.id().equals(choices.get(id))) emit(connection, total, region);
            } else {
                for (Connection connection : outs) emit(connection, total, region);
            }
        }

        private void emit(Connection connection, Flow sent, Region region) {
            if (region != null && !region.inside().contains(connection.to()) && !connection.to().equals(region.join())) return;
            BigDecimal value = sent.value();
            BigDecimal loss = connection.lossRate();
            if (loss != null && loss.signum() > 0 && value.signum() > 0) {
                value = rounded ? FlowShares.arrived(value, loss) : value.multiply(BigDecimal.ONE.subtract(loss), MC);
            }
            String unit = arrivalUnit(connection);
            if (unit == null) unit = sent.unit();
            arrived.put(connection.id(), new Flow(convert(value, sent.unit(), unit, connection.id()), unit));
        }
    }
}
