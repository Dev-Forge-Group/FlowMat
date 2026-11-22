package org.myweb.flowmat.domain.flowrun.application.publicapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Connection;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Node;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Port;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Requirement;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Result;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Step;

/**
 * Planning backward from a target (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 10): a data
 * pipeline that is not manufacturing, properties (a) and (e) on seeded random graphs, and the graphs it refuses.
 */
class FlowDemandTest {

    private static final MathContext MC = MathContext.DECIMAL128;

    /** Records through a pipeline: a filter drops 20%, 70% is transformed, the rest reviewed (10% dropped), and both merge. */
    @Test
    void aDataPipelineNeedsWhatItsLossesAndShareAskFor() {
        Graph graph = new Graph();
        graph.node("ingest", "process").port("ingest.out", "output", "1", "record", "data", "events");
        graph.node("split", "split");
        graph.node("transform", "process").port("transform.in", "input", "1", "record").port("transform.out", "output", "1", "record");
        graph.node("review", "process").port("review.in", "input", "1", "record").port("review.out", "output", "1", "record");
        graph.node("merge", "join");
        graph.node("load", "process").port("load.in", "input", "1", "record").port("load.out", "output", "1", "record");
        graph.connect("c1", "ingest", "ingest.out", "split", null).loss("0.2");
        graph.connect("c2", "split", null, "transform", "transform.in").share("share", "70");
        graph.connect("c3", "split", null, "review", "review.in").share("rest", null);
        graph.connect("c4", "transform", "transform.out", "merge", null);
        graph.connect("c5", "review", "review.out", "merge", null).loss("0.1");
        graph.connect("c6", "merge", null, "load", "load.in");

        Result result = graph.plan("load.out", "1000");

        // 1000 = X(0.7 + 0.3 × 0.9) at the Split, and what is sent is X / 0.8: 1288.6597938…, rounded up.
        assertThat(result.feasible()).isTrue();
        assertThat(result.requirements()).hasSize(1);
        Requirement ingest = result.requirements().get(0);
        assertThat(ingest.at()).isEqualTo("ingest");
        assertThat(ingest.resource()).isEqualTo("data");
        assertThat(ingest.quantity()).isEqualByComparingTo("1288.6598");
        assertThat(ingest.unit()).isEqualTo("record");
        assertThat(ingest.trace()).extracting(Step::rule)
            .containsExactly("ratio", "ratio", "pass", "join", "loss", "ratio", "ratio", "round");
        assertThat(ingest.trace()).filteredOn(step -> step.rule().equals("join")).singleElement()
            .satisfies(step -> assertThat(step.to()).isEqualByComparingTo("1030.9278"));
        assertThat(demand(result, "transform").batches()).isEqualByComparingTo("721.6495");
        assertThat(demand(result, "review").batches()).isEqualByComparingTo("309.2784");
        assertThat(demand(result, "merge").through()).isEqualByComparingTo("1000");
        assertThat(result.surplus()).isEmpty();
        assertThat(result.assumptions()).isEmpty();
        assertThat(delivered(graph, result, "load.out")).isEqualByComparingTo("1000.0000");
    }

    /**
     * (a) What the plan says to put in, run forward with the run's rounding, brings at least the target, and not much more.
     * (e) The trace's factors multiply the target into each requirement.
     */
    @Test
    void planningAndRunningForwardAgreeOnRandomGraphs() {
        for (int seed = 1; seed <= 300; seed++) {
            Random random = new Random(seed);
            RandomGraph generated = RandomGraph.of(random);
            BigDecimal target = BigDecimal.valueOf(1 + random.nextInt(500_000), 2);
            Result result = generated.graph.plan(generated.target, target.toPlainString());
            String context = "seed " + seed;

            assertThat(result.feasible()).as(context).isTrue();
            BigDecimal delivered = delivered(generated.graph, result, generated.target);
            assertThat(delivered).as(context).isGreaterThanOrEqualTo(target);
            assertThat(delivered).as(context).isLessThanOrEqualTo(target.multiply(new BigDecimal("1.001")).add(BigDecimal.ONE));

            for (Requirement requirement : result.requirements()) {
                BigDecimal product = target;
                for (Step step : requirement.trace()) product = product.multiply(step.factor(), MC);
                assertThat(product.subtract(requirement.quantity()).abs())
                    .as(context + " " + requirement.at()).isLessThan(new BigDecimal("1E-20").multiply(requirement.quantity().max(BigDecimal.ONE)));
                assertThat(requirement.trace().get(0).from()).as(context).isEqualByComparingTo(target);
                for (int i = 1; i < requirement.trace().size(); i++) {
                    assertThat(requirement.trace().get(i).from()).as(context).isEqualByComparingTo(requirement.trace().get(i - 1).to());
                }
            }
        }
    }

    @Test
    void refusesWhatItCannotPlan() {
        Graph apart = new Graph();
        apart.node("a", "process").port("a.out", "output", "1", "kg");
        apart.node("b", "process").port("b.out", "output", "1", "kg");
        apart.node("gather", "join");
        apart.node("pack", "process").port("pack.in", "input", "1", "kg").port("pack.out", "output", "1", "kg");
        apart.connect("ca", "a", "a.out", "gather", null);
        apart.connect("cb", "b", "b.out", "gather", null);
        apart.connect("cp", "gather", null, "pack", "pack.in");
        assertThatThrownBy(() -> apart.plan("pack.out", "10")).isInstanceOf(FlowDemand.Refusal.class)
            .satisfies(refusal -> assertThat(((FlowDemand.Refusal) refusal).at()).isEqualTo("gather"));

        Graph cycle = new Graph();
        cycle.node("a", "process").port("a.in", "input", "1", "kg").port("a.out", "output", "1", "kg");
        cycle.node("b", "process").port("b.in", "input", "1", "kg").port("b.out", "output", "1", "kg");
        cycle.connect("ab", "a", "a.out", "b", "b.in");
        cycle.connect("ba", "b", "b.out", "a", "a.in");
        assertThatThrownBy(() -> cycle.plan("b.out", "10")).isInstanceOf(FlowDemand.Refusal.class).hasMessageContaining("cycle");

        Graph units = new Graph();
        units.node("tank", "process").port("tank.out", "output", "1", "kg");
        units.node("fill", "process").port("fill.in", "input", "1", "L").port("fill.out", "output", "1", "ea");
        units.connect("tf", "tank", "tank.out", "fill", "fill.in");
        assertThatThrownBy(() -> units.plan("fill.out", "10")).isInstanceOf(FlowDemand.Refusal.class)
            .satisfies(refusal -> assertThat(((FlowDemand.Refusal) refusal).at()).isEqualTo("tf"));
    }

    private static FlowDemand.NodeDemand demand(Result result, String nodeId) {
        return result.nodes().stream().filter(node -> node.nodeId().equals(nodeId)).findFirst().orElseThrow();
    }

    // -------------------------------------------------------------------- an independent forward run

    /**
     * Runs the requirements forward the way a run routes (Split shares and losses from {@link FlowShares}, 4 decimals), with
     * nodes in batches limited by their least supplied input. Nodes are in the order built, which is topological.
     */
    private static BigDecimal delivered(Graph graph, Result result, String targetPort) {
        Map<String, BigDecimal> supply = new HashMap<>();
        for (Requirement requirement : result.requirements()) supply.put(requirement.at(), requirement.quantity());
        Map<String, BigDecimal> arrived = new HashMap<>();
        Map<String, BigDecimal> made = new HashMap<>();
        for (Node node : graph.nodes) {
            List<Connection> ins = graph.connections.stream().filter(c -> c.to().equals(node.id())).toList();
            List<Connection> outs = graph.connections.stream().filter(c -> c.from().equals(node.id())).toList();
            List<Port> inPorts = graph.ports.stream().filter(p -> p.nodeId().equals(node.id()) && p.direction().equals("input")).toList();
            List<Port> outPorts = graph.ports.stream().filter(p -> p.nodeId().equals(node.id()) && p.direction().equals("output")).toList();
            if (!inPorts.isEmpty() || !outPorts.isEmpty()) {
                BigDecimal k = null;
                for (Port input : inPorts) {
                    BigDecimal available = ins.stream().anyMatch(c -> input.id().equals(c.toPort()))
                        ? ins.stream().filter(c -> input.id().equals(c.toPort()))
                            .map(c -> arrived.getOrDefault(c.id(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add)
                        : supply.getOrDefault(input.id(), BigDecimal.ZERO);
                    BigDecimal ratio = available.divide(input.quantity(), MC);
                    k = k == null ? ratio : k.min(ratio);
                }
                if (k == null) k = supply.getOrDefault(node.id(), BigDecimal.ZERO).divide(outPorts.get(0).quantity(), MC);
                for (Port output : outPorts) {
                    BigDecimal amount = k.multiply(output.quantity(), MC);
                    made.put(output.id(), amount);
                    for (Connection c : outs) if (output.id().equals(c.fromPort())) send(c, amount, arrived);
                }
                continue;
            }
            BigDecimal total = ins.isEmpty() ? supply.getOrDefault(node.id(), BigDecimal.ZERO)
                : ins.stream().map(c -> arrived.getOrDefault(c.id(), BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
            switch (node.kind()) {
                case "split" -> {
                    Map<String, BigDecimal> amounts = FlowShares.split(total, outs.stream()
                        .map(c -> new FlowShares.Share(c.id(), c.splitKind(), c.splitValue())).toList()).amounts();
                    for (Connection c : outs) if (amounts.get(c.id()).signum() > 0) send(c, amounts.get(c.id()), arrived);
                }
                case "exclusive" -> send(graph.chosen.get(node.id()), total, arrived);
                default -> outs.forEach(c -> send(c, total, arrived));
            }
        }
        return made.get(targetPort);
    }

    private static void send(Connection connection, BigDecimal amount, Map<String, BigDecimal> arrived) {
        BigDecimal value = connection.lossRate() == null || amount.signum() == 0 ? amount : FlowShares.arrived(amount, connection.lossRate());
        arrived.put(connection.id(), value);
    }

    // -------------------------------------------------------------------- building graphs

    private static final class Graph {
        private final List<Node> nodes = new ArrayList<>();
        private final List<Port> ports = new ArrayList<>();
        private final List<Connection> connections = new ArrayList<>();
        /** The connection each Exclusive takes: the one toward the target, which the default choice picks too. */
        private final Map<String, Connection> chosen = new HashMap<>();
        private String current;

        Graph node(String id, String kind) {
            nodes.add(new Node(id, kind, List.of()));
            current = id;
            return this;
        }

        Graph port(String id, String direction, String quantity, String unit) {
            return port(id, direction, quantity, unit, "material", null);
        }

        Graph port(String id, String direction, String quantity, String unit, String resource, String ref) {
            ports.add(new Port(id, current, direction, new BigDecimal(quantity), unit, resource, ref, null));
            return this;
        }

        Link connect(String id, String from, String fromPort, String to, String toPort) {
            connections.add(new Connection(id, from, to, fromPort, toPort, null, null, null, null, null, 0, false));
            return new Link(this, connections.size() - 1);
        }

        Result plan(String targetPort, String quantity) {
            return FlowDemand.plan(new FlowDemand.Request(new FlowDemand.Graph(nodes, ports, connections),
                new FlowDemand.Target(targetPort, null, new BigDecimal(quantity), null), Map.of(), FlowDemand.Units.none()));
        }
    }

    private record Link(Graph graph, int index) {
        Link loss(String rate) {
            Connection c = graph.connections.get(index);
            graph.connections.set(index, new Connection(c.id(), c.from(), c.to(), c.fromPort(), c.toPort(), null, null,
                new BigDecimal(rate), c.splitKind(), c.splitValue(), c.priority(), c.defaultRoute()));
            return this;
        }

        Link share(String kind, String value) {
            Connection c = graph.connections.get(index);
            graph.connections.set(index, new Connection(c.id(), c.from(), c.to(), c.fromPort(), c.toPort(), null, null,
                c.lossRate(), kind, value == null ? null : new BigDecimal(value), c.priority(), c.defaultRoute()));
            return this;
        }

        Link priority(int priority) {
            Connection c = graph.connections.get(index);
            graph.connections.set(index, new Connection(c.id(), c.from(), c.to(), c.fromPort(), c.toPort(), null, null,
                c.lossRate(), c.splitKind(), c.splitValue(), priority, c.defaultRoute()));
            return this;
        }

        Connection connection() {
            return graph.connections.get(index);
        }
    }

    /**
     * Seeded series-parallel graphs: a start, then blocks of nodes in batches (with materials from nothing and byproducts),
     * Split→branches→Join diamonds (a percent and the rest or two percents, sometimes a fixed share to a dead end) and
     * Exclusives with a dead-end branch, ending at a node whose output is the target. Connections lose 0–20%.
     */
    private static final class RandomGraph {
        private static final String[] QUANTITIES = {"1", "2", "2.5", "4", "0.5", "10", "3"};
        private static final String[] LOSSES = {null, null, "0.05", "0.1", "0.2", "0.03"};
        private final Graph graph = new Graph();
        private final Random random;
        private int count;
        private String target;

        private record End(String node, String port) {
        }

        private RandomGraph(Random random) {
            this.random = random;
        }

        static RandomGraph of(Random random) {
            RandomGraph generated = new RandomGraph(random);
            End end;
            if (random.nextInt(3) == 0) {
                generated.graph.node("start", "start");
                end = new End("start", null);
            } else {
                generated.graph.node("source", "process").port("source.out", "output", generated.quantity(), "kg");
                end = new End("source", "source.out");
            }
            int blocks = 1 + random.nextInt(4);
            for (int i = 0; i < blocks; i++) end = generated.block(end, 0);
            End last = generated.process(end);
            generated.target = last.port();
            return generated;
        }

        private End block(End from, int depth) {
            int pick = random.nextInt(depth < 2 ? 8 : 5);
            if (pick <= 2) return process(from);
            if (pick == 3) return exclusive(from);
            if (pick == 4) return fork(from);
            return diamond(from, depth);
        }

        /** A Split with one branch on toward the target and the others to dead ends, so its own rule sets its input. */
        private End fork(End from) {
            String split = "s" + ++count;
            graph.node(split, "split");
            link(from, split, null);
            int percent = 10 + random.nextInt(81);
            int pattern = random.nextInt(4);
            String main = pattern == 0 || pattern == 3 ? "share" : "rest";
            String mainValue = main.equals("share") ? String.valueOf(percent) : null;
            String dead = "d" + ++count;
            graph.node(dead, "process").port(dead + ".in", "input", "1", "kg").port(dead + ".out", "output", "1", "kg");
            switch (pattern) {
                case 0 -> graph.connect(split + "-" + dead, split, null, dead, dead + ".in").share("rest", null);
                case 1 -> graph.connect(split + "-" + dead, split, null, dead, dead + ".in").share("share", String.valueOf(percent));
                case 2 -> graph.connect(split + "-" + dead, split, null, dead, dead + ".in").share("fixed", String.valueOf(1 + random.nextInt(20)));
                default -> graph.connect(split + "-" + dead, split, null, dead, dead + ".in").share("share", String.valueOf(100 - percent));
            }
            // The next block's first connection leaves the Split: give it the main share.
            pendingShares.put(split, new String[] {main, mainValue});
            return new End(split, null);
        }

        private final Map<String, String[]> pendingShares = new HashMap<>();

        private End process(End from) {
            String id = "p" + ++count;
            graph.node(id, "process").port(id + ".in", "input", quantity(), "kg");
            for (int m = random.nextInt(3); m > 0; m--) graph.port(id + ".m" + m, "input", quantity(), "kg");
            graph.port(id + ".out", "output", quantity(), "kg");
            if (random.nextInt(4) == 0) graph.port(id + ".by", "output", quantity(), "kg");
            link(from, id, id + ".in");
            return new End(id, id + ".out");
        }

        private End diamond(End from, int depth) {
            String split = "s" + ++count;
            graph.node(split, "split");
            link(from, split, null);
            // Named now, added after its branches so that nodes stay in topological order.
            String join = "j" + ++count;
            int percent = 10 + random.nextInt(81);
            int pattern = random.nextInt(3);
            List<End> ends = new ArrayList<>();
            List<Integer> firsts = new ArrayList<>();
            for (int branch = 0; branch < 2; branch++) {
                firsts.add(graph.connections.size());
                if (random.nextInt(3) == 0) {
                    link(new End(split, null), join, null);
                    ends.add(null);
                } else {
                    // The first connection a block makes leaves the Split.
                    ends.add(block(new End(split, null), depth + 1));
                }
            }
            new Link(graph, firsts.get(0)).share("share", String.valueOf(percent));
            new Link(graph, firsts.get(1)).share(pattern == 1 ? "share" : "rest", pattern == 1 ? String.valueOf(100 - percent) : null);
            if (pattern == 2) {
                String dead = "d" + ++count;
                graph.node(dead, "process").port(dead + ".in", "input", "1", "kg").port(dead + ".out", "output", "1", "kg");
                graph.connect(split + "-" + dead, split, null, dead, dead + ".in").share("fixed", String.valueOf(1 + random.nextInt(20)));
            }
            graph.node(join, "join");
            for (End end : ends) if (end != null) link(end, join, null);
            return new End(join, null);
        }

        private End exclusive(End from) {
            String id = "x" + ++count;
            graph.node(id, "exclusive");
            link(from, id, null);
            String dead = "d" + ++count;
            graph.node(dead, "process").port(dead + ".in", "input", "1", "kg").port(dead + ".out", "output", "1", "kg");
            graph.connect(id + "-" + dead, id, null, dead, dead + ".in").priority(1 + random.nextInt(2));
            return new End(id, null);
        }

        private void link(End from, String to, String toPort) {
            String id = from.node() + "-" + to + "-" + graph.connections.size();
            Link link = graph.connect(id, from.node(), from.port(), to, toPort);
            String loss = LOSSES[random.nextInt(LOSSES.length)];
            if (loss != null) link.loss(loss);
            String[] share = pendingShares.remove(from.node());
            if (share != null) link.share(share[0], share[1]);
            if (graph.nodes.stream().anyMatch(node -> node.id().equals(from.node()) && node.kind().equals("exclusive"))) {
                link.priority(random.nextInt(3));
                graph.chosen.put(from.node(), link.connection());
            }
        }

        private String quantity() {
            return QUANTITIES[random.nextInt(QUANTITIES.length)];
        }
    }

}
