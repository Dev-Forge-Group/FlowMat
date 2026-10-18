package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Runtime interpretation of a published revision; draft rows are never consulted. */
final class FlowRunGraph {
    private static final Set<String> POLICIES = Set.of("stop", "skip", "retry");

    record Route(String connectionId, String targetNodeId) {}
    /** What a connection's loss took on the way (ADR-009 decision 3): {@code sent} = {@code arrived} + {@code lost}. */
    record Loss(BigDecimal sent, BigDecimal rate, BigDecimal lost, BigDecimal arrived) {}

    /**
     * What a finished step's output does on one outgoing connection. {@code reason} is set for an Exclusive gateway's
     * connections (ADR-006): {@code first_match}, {@code default}, {@code condition_false}, {@code exclusive_not_chosen} or
     * {@code default_not_needed}; for a Split's (ADR-008): {@code split} or {@code split_empty}; and {@code loss_empty} when a
     * connection's loss leaves nothing (ADR-009). It is null for an ordinary connection, which is filtered only by its
     * condition. {@code quantity} is what the next step receives when that is not the output's: a Split's share, or what is
     * left after the connection's {@code loss}.
     */
    record Decision(String connectionId, String targetNodeId, boolean willRoute, String reason, BigDecimal quantity,
        Loss loss) {
        Decision(String connectionId, String targetNodeId, boolean willRoute, String reason, BigDecimal quantity) {
            this(connectionId, targetNodeId, willRoute, reason, quantity, null);
        }

        Decision(String connectionId, String targetNodeId, boolean willRoute, String reason) {
            this(connectionId, targetNodeId, willRoute, reason, null, null);
        }

        Decision(String connectionId, String targetNodeId, boolean willRoute) {
            this(connectionId, targetNodeId, willRoute, null, null, null);
        }
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * A node's execution policy from its published revision; nodes without one run as before: no time limit, 3 retries
     * at once, no concurrency limit (docs/domain/flow-run-execution-policy.md EP1, EP3).
     */
    record NodePolicy(Integer timeoutSeconds, int retryLimit, int retryDelaySeconds, boolean exponential,
        Integer maxRetryDelaySeconds, Integer concurrencyLimit) {
        static final NodePolicy DEFAULT = new NodePolicy(null, 3, 0, false, null, null);

        /** Seconds to wait before retry {@code retryNumber} (1 first): fixed, or doubled each time up to the cap (EP4). */
        long delayBeforeRetry(int retryNumber) {
            if (retryDelaySeconds == 0) return 0;
            if (!exponential) return retryDelaySeconds;
            // The delay is at most 86 400 (< 2^17) and the shift at most 40, so this cannot overflow.
            long delay = (long) retryDelaySeconds << Math.min(Math.max(retryNumber - 1, 0), 40);
            return maxRetryDelaySeconds == null ? delay : Math.min(delay, maxRetryDelaySeconds);
        }
    }

    private record PortRef(String processId, String direction) {}

    /** {@code priority} and {@code order} (as drawn) rank an Exclusive gateway's connections; {@code defaultRoute} is its fallback. */
    private record Edge(String id, String target, String fromIoId, String toIoId,
        ConditionExpression condition, BigDecimal capacity, String unit, String failurePolicy, int priority,
        boolean defaultRoute, int order, String splitKind, BigDecimal splitValue, BigDecimal lossRate,
        BigDecimal delaySeconds) {}

    private final List<String> roots;
    private final Map<String, List<Edge>> outgoing;
    private final Map<String, Edge> byId;
    private final Map<String, ConditionExpression> portRules;
    private final Map<String, PortSchema> portSchemas;
    private final Map<String, NodePolicy> policies;
    private final Set<String> exclusiveNodes;
    private final Set<String> joinNodes;
    private final Set<String> splitNodes;
    /** The connections into each node, as drawn. */
    private final Map<String, List<String>> incoming;

    private FlowRunGraph(List<String> roots, Map<String, List<Edge>> outgoing, Map<String, Edge> byId,
        Map<String, ConditionExpression> portRules, Map<String, PortSchema> portSchemas, Map<String, NodePolicy> policies,
        Set<String> exclusiveNodes, Set<String> joinNodes, Map<String, List<String>> incoming, Set<String> splitNodes) {
        this.roots = List.copyOf(roots);
        this.outgoing = outgoing;
        this.byId = byId;
        this.portRules = portRules;
        this.portSchemas = portSchemas;
        this.policies = policies;
        this.exclusiveNodes = Set.copyOf(exclusiveNodes);
        this.joinNodes = Set.copyOf(joinNodes);
        this.splitNodes = Set.copyOf(splitNodes);
        this.incoming = incoming;
    }

    static FlowRunGraph from(JsonNode snapshot) {
        JsonNode processes = snapshot.path("processes");
        JsonNode connections = snapshot.path("connections");
        if (!processes.isArray() || processes.isEmpty() || !connections.isArray()) {
            throw conflict("Published revision has no executable graph.");
        }
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Set<String> exclusiveNodes = new HashSet<>();
        Set<String> joinNodes = new HashSet<>();
        Set<String> splitNodes = new HashSet<>();
        for (JsonNode process : processes) {
            String id = requiredText(process, "processId");
            if (indegree.putIfAbsent(id, 0) != null) {
                throw conflict("Published revision has duplicate process IDs.");
            }
            // Revisions published before ADR-006 have no gateways and route as before.
            if ("exclusive".equalsIgnoreCase(textOrNull(process.path("nodeType")))) exclusiveNodes.add(id);
            if ("join".equalsIgnoreCase(textOrNull(process.path("nodeType")))) joinNodes.add(id);
            if ("split".equalsIgnoreCase(textOrNull(process.path("nodeType")))) splitNodes.add(id);
        }
        Map<String, NodePolicy> policies = new HashMap<>();
        JsonNode nodePolicies = snapshot.path("nodePolicies");
        if (!nodePolicies.isMissingNode() && !nodePolicies.isNull()) {
            if (!nodePolicies.isArray()) {
                throw conflict("Published revision has invalid nodePolicies.");
            }
            for (JsonNode policy : nodePolicies) {
                String processId = requiredText(policy, "processId");
                if (!indegree.containsKey(processId) || policies.putIfAbsent(processId, policy(policy, processId)) != null) {
                    throw conflict("Published revision has an invalid node policy: " + processId);
                }
            }
        }
        Map<String, String> portUnits = new HashMap<>();
        Map<String, PortRef> portRefs = new HashMap<>();
        Map<String, ConditionExpression> portRules = new HashMap<>();
        Map<String, PortSchema> portSchemas = new HashMap<>();
        JsonNode ports = snapshot.path("processIos");
        if (!ports.isMissingNode() && !ports.isArray()) {
            throw conflict("Published revision has invalid processIos.");
        }
        if (ports.isArray()) {
            for (JsonNode port : ports) {
                String id = textOrNull(port.path("processIoId"));
                if (id == null || portRefs.putIfAbsent(id,
                    new PortRef(textOrNull(port.path("processId")), textOrNull(port.path("direction")))) != null) {
                    throw conflict("Published revision has an invalid or duplicate processIoId.");
                }
                String unit = textOrNull(port.path("unit"));
                if (id != null && unit != null) portUnits.put(id, unit);
                String validationRule = textOrNull(port.path("validationRule"));
                if (id != null && validationRule != null) {
                    portRules.put(id, compilePublished(validationRule,
                        "port " + id + " validationRule"));
                }
                if (id != null && port.hasNonNull("schemaJson")) {
                    try {
                        portSchemas.put(id, PortSchema.parse(port.get("schemaJson")));
                    } catch (BusinessException exception) {
                        throw conflict("Published revision port " + id + " has invalid schemaJson.");
                    }
                }
            }
        }
        Map<String, List<Edge>> outgoing = new HashMap<>();
        Map<String, Edge> byId = new HashMap<>();
        Map<String, List<String>> incoming = new HashMap<>();
        // Revisions published before ADR-009 (schemaVersion 1) carry loss rates that do not apply (decision 8), and those
        // before ADR-010 (schemaVersion 2) delays (decision 7).
        int schemaVersion = snapshot.path("schemaVersion").asInt(1);
        boolean lossApplies = schemaVersion >= 2;
        boolean delayApplies = schemaVersion >= 3;
        for (JsonNode connection : connections) {
            String id = requiredText(connection, "connectionId");
            String source = requiredText(connection, "fromProcessId");
            String target = requiredText(connection, "toProcessId");
            if (!indegree.containsKey(source) || !indegree.containsKey(target)) {
                throw conflict("Published revision connection refers to a missing process: " + id);
            }
            String expression = textOrNull(connection.path("conditionExpr"));
            ConditionExpression condition = expression == null ? null
                : compilePublished(expression, "connection " + id + " conditionExpr");
            JsonNode capacityNode = connection.path("capacity");
            BigDecimal capacity = null;
            if (!capacityNode.isMissingNode() && !capacityNode.isNull()) {
                if (!capacityNode.isNumber() || capacityNode.decimalValue().signum() < 0) {
                    throw conflict("Published revision has invalid capacity: " + id);
                }
                capacity = capacityNode.decimalValue();
            }
            String policy = textOrNull(connection.path("failurePolicy"));
            if (policy == null) policy = "stop";
            if (!POLICIES.contains(policy)) {
                throw conflict("Published revision has invalid failure policy: " + id);
            }
            String fromIoId = textOrNull(connection.path("fromIoId"));
            String toIoId = textOrNull(connection.path("toIoId"));
            requirePortReference(portRefs, fromIoId, source, "output", id);
            requirePortReference(portRefs, toIoId, target, "input", id);
            String unit = textOrNull(connection.path("unit"));
            if (unit == null) unit = portUnits.get(fromIoId);
            JsonNode priorityNode = connection.path("priority");
            int priority = priorityNode.isIntegralNumber() && priorityNode.canConvertToInt() ? priorityNode.intValue() : 0;
            boolean defaultRoute = connection.path("defaultRoute").asBoolean(false);
            String splitKind = textOrNull(connection.path("splitKind"));
            JsonNode splitValueNode = connection.path("splitValue");
            BigDecimal splitValue = splitValueNode.isNumber() ? splitValueNode.decimalValue() : null;
            JsonNode lossNode = connection.path("lossRate");
            BigDecimal lossRate = null;
            if (lossApplies && lossNode.isNumber() && lossNode.decimalValue().signum() != 0) {
                lossRate = lossNode.decimalValue();
                // Publishing refuses it (LOSS_RATE_RANGE).
                if (lossRate.signum() < 0 || lossRate.compareTo(BigDecimal.ONE) >= 0) {
                    throw conflict("Published revision has an invalid lossRate: " + id);
                }
            }
            JsonNode delayNode = connection.path("delayTimeSec");
            BigDecimal delaySeconds = null;
            if (delayApplies && delayNode.isNumber() && delayNode.decimalValue().signum() != 0) {
                delaySeconds = delayNode.decimalValue();
                // Publishing refuses it (DELAY_RANGE).
                if (delaySeconds.signum() < 0) {
                    throw conflict("Published revision has an invalid delayTimeSec: " + id);
                }
            }
            Edge edge = new Edge(id, target, fromIoId, toIoId, condition, capacity, unit, policy, priority, defaultRoute,
                byId.size(), splitKind, splitValue, lossRate, delaySeconds);
            if (byId.putIfAbsent(id, edge) != null) {
                throw conflict("Published revision has duplicate connection IDs.");
            }
            outgoing.computeIfAbsent(source, ignored -> new ArrayList<>()).add(edge);
            incoming.computeIfAbsent(target, ignored -> new ArrayList<>()).add(id);
            indegree.put(target, indegree.get(target) + 1);
        }
        for (String join : joinNodes) {
            // Publishing refuses it (JOIN_WITHOUT_INPUT); a Join with nothing to wait for would start as a root.
            if (indegree.get(join) == 0) {
                throw conflict("Published revision Join " + join + " has no incoming connection.");
            }
        }
        for (String split : splitNodes) {
            // Publishing refuses it (SPLIT_WITHOUT_INPUT); a Split with nothing to divide would start as a root.
            if (indegree.get(split) == 0) {
                throw conflict("Published revision Split " + split + " has no incoming connection.");
            }
        }
        List<String> roots = indegree.entrySet().stream()
            .filter(entry -> entry.getValue() == 0).map(Map.Entry::getKey).toList();
        Map<String, Integer> remaining = new HashMap<>(indegree);
        ArrayDeque<String> queue = new ArrayDeque<>(roots);
        int visited = 0;
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            visited++;
            for (Edge edge : outgoing.getOrDefault(node, List.of())) {
                int count = remaining.merge(edge.target(), -1, Integer::sum);
                if (count == 0) queue.addLast(edge.target());
            }
        }
        if (visited != indegree.size()) {
            throw conflict("Graph revisions with cycles cannot execute.");
        }
        return new FlowRunGraph(roots, outgoing, byId, portRules, portSchemas, policies, exclusiveNodes, joinNodes, incoming,
            splitNodes);
    }

    List<String> roots() {
        return roots;
    }

    /** Whether the node is an Exclusive gateway, which the server passes at once (ADR-006 decision 4). */
    boolean exclusive(String nodeId) {
        return exclusiveNodes.contains(nodeId);
    }

    /** Whether the node is a Split gateway, which divides what arrives and the server passes at once (ADR-008). */
    boolean split(String nodeId) {
        return splitNodes.contains(nodeId);
    }

    /** Whether the node is a gateway the server passes at once: an Exclusive, a Join or a Split (ADR-006 to ADR-008). */
    boolean gateway(String nodeId) {
        return exclusiveNodes.contains(nodeId) || joinNodes.contains(nodeId) || splitNodes.contains(nodeId);
    }

    /** The connection's delay (docs/architecture/adr/ADR-010-connection-delay.md), null without one or before ADR-010. */
    Duration delay(String connectionId) {
        BigDecimal seconds = delaySeconds(connectionId);
        return seconds.signum() == 0 ? null : Duration.ofNanos(seconds.movePointRight(9).longValue());
    }

    /** The connection's delay in seconds as published, 0 without one. */
    BigDecimal delaySeconds(String connectionId) {
        Edge edge = byId.get(connectionId);
        return edge == null || edge.delaySeconds() == null ? BigDecimal.ZERO : edge.delaySeconds();
    }

    /** Whether what goes along the connection is not the step's output: a Split's share or a loss (ADR-008, ADR-009). */
    boolean quantityChanges(String nodeId, String connectionId) {
        Edge edge = byId.get(connectionId);
        return splitNodes.contains(nodeId) || edge != null && edge.lossRate() != null;
    }

    /** The output with its quantity replaced: one share of a Split (ADR-008 decision 7), or what is left after a loss. */
    static JsonNode withQuantity(JsonNode output, BigDecimal quantity) {
        ObjectNode copy = ((ObjectNode) output).deepCopy();
        copy.put("quantity", quantity);
        return copy;
    }

    /** Whether the node is a Join gateway, which waits for its branches and the server passes on once (ADR-007). */
    boolean join(String nodeId) {
        return joinNodes.contains(nodeId);
    }

    /**
     * How many connections into the Join a step at one of {@code liveNodes} can still route along, now or further on
     * (ADR-007 decision 2). The graph has no cycles, so the walk ends; 0 means nothing more can arrive.
     */
    int stillComing(String joinNodeId, Collection<String> liveNodes) {
        Set<String> reachable = new HashSet<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>(liveNodes);
        while (!queue.isEmpty()) {
            String node = queue.removeFirst();
            if (!seen.add(node)) continue;
            for (Edge edge : outgoing.getOrDefault(node, List.of())) {
                reachable.add(edge.id());
                queue.addLast(edge.target());
            }
        }
        return (int) incoming.getOrDefault(joinNodeId, List.of()).stream().filter(reachable::contains).count();
    }

    NodePolicy policy(String nodeId) {
        return policies.getOrDefault(nodeId, NodePolicy.DEFAULT);
    }

    String failurePolicy(String sourceConnectionId) {
        if (sourceConnectionId == null) return "stop";
        Edge edge = byId.get(sourceConnectionId);
        if (edge == null) throw conflict("Step source connection is missing from its published revision.");
        return edge.failurePolicy();
    }

    List<Route> routes(String nodeId, JsonNode output) {
        return preview(nodeId, output).stream().filter(Decision::willRoute)
            .map(decision -> new Route(decision.connectionId(), decision.targetNodeId())).toList();
    }

    List<Decision> preview(String nodeId, JsonNode output) {
        if (output == null || !output.isObject()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Graph step outputSnapshot must be an object.");
        }
        Map<String, Object> values = values(output);
        if (exclusiveNodes.contains(nodeId)) {
            return exclusivePreview(nodeId, output, values);
        }
        if (splitNodes.contains(nodeId)) {
            return splitPreview(nodeId, output, values);
        }
        List<Decision> decisions = new ArrayList<>();
        for (Edge edge : outgoing.getOrDefault(nodeId, List.of())) {
            if (edge.condition() != null && !edge.condition().evaluate(values)) {
                decisions.add(new Decision(edge.id(), edge.target(), false));
                continue;
            }
            decisions.add(transfer(edge, output, values, null, null));
        }
        return decisions;
    }

    /**
     * An Exclusive gateway's routes (ADR-006 decisions 2-3): its connections by priority, lowest first and then in the order
     * they were drawn. The first one whose condition holds goes on; else its default connection; else none.
     */
    private List<Decision> exclusivePreview(String nodeId, JsonNode output, Map<String, Object> values) {
        List<Edge> edges = outgoing.getOrDefault(nodeId, List.of()).stream()
            .sorted(Comparator.comparingInt(Edge::priority).thenComparingInt(Edge::order))
            .toList();
        Edge chosen = null;
        String reason = null;
        for (Edge edge : edges) {
            if (!edge.defaultRoute() && (edge.condition() == null || edge.condition().evaluate(values))) {
                chosen = edge;
                reason = "first_match";
                break;
            }
        }
        if (chosen == null) {
            chosen = edges.stream().filter(Edge::defaultRoute).findFirst().orElse(null);
            reason = "default";
        }
        Decision routed = chosen == null ? null : transfer(chosen, output, values, reason, null);
        List<Decision> decisions = new ArrayList<>();
        for (Edge edge : edges) {
            if (edge == chosen) {
                decisions.add(routed);
            } else if (edge.defaultRoute()) {
                decisions.add(new Decision(edge.id(), edge.target(), false, "default_not_needed"));
            } else if (edge.condition() != null && !edge.condition().evaluate(values)) {
                decisions.add(new Decision(edge.id(), edge.target(), false, "condition_false"));
            } else {
                decisions.add(new Decision(edge.id(), edge.target(), false, "exclusive_not_chosen"));
            }
        }
        return decisions;
    }

    /**
     * A Split gateway's shares (ADR-008 decisions 2-7), in the order drawn: fixed quantities, percents of the whole rounded to
     * 4 decimals, and the rest; the rounding difference goes to the rest, else to the last percent drawn, so the shares add up
     * to the whole. A share of 0 does not go on. Each share passes its connection's transfer rules with its own quantity.
     */
    private List<Decision> splitPreview(String nodeId, JsonNode output, Map<String, Object> values) {
        if (!(values.get("quantity") instanceof BigDecimal total)) {
            throw conflict("Split " + nodeId + " needs outputSnapshot.quantity to divide.");
        }
        if (total.signum() < 0) {
            throw conflict("Split " + nodeId + " cannot divide a negative quantity.");
        }
        List<Edge> edges = outgoing.getOrDefault(nodeId, List.of());
        if (edges.isEmpty()) {
            // A Split that leads nowhere (SPLIT_SINGLE_ROUTE warns) ends here like any last step.
            return List.of();
        }
        Map<String, BigDecimal> amounts = new HashMap<>();
        BigDecimal assigned = BigDecimal.ZERO;
        Edge rest = null;
        Edge lastShare = null;
        for (Edge edge : edges) {
            if ("rest".equals(edge.splitKind())) {
                rest = edge;
                continue;
            }
            BigDecimal amount;
            if ("share".equals(edge.splitKind()) && edge.splitValue() != null) {
                amount = total.multiply(edge.splitValue()).divide(HUNDRED, 4, RoundingMode.HALF_UP);
                lastShare = edge;
            } else if ("fixed".equals(edge.splitKind()) && edge.splitValue() != null) {
                amount = edge.splitValue();
            } else {
                throw conflict("Published revision Split " + nodeId + " connection " + edge.id() + " has no share.");
            }
            amounts.put(edge.id(), amount);
            assigned = assigned.add(amount);
        }
        BigDecimal left = total.subtract(assigned);
        if (rest != null) {
            amounts.put(rest.id(), left);
        } else if (lastShare != null) {
            amounts.merge(lastShare.id(), left, BigDecimal::add);
        } else if (left.signum() != 0) {
            throw conflict("Split " + nodeId + " leaves " + plain(left) + " undivided.");
        }
        if (amounts.values().stream().anyMatch(amount -> amount.signum() < 0)) {
            throw conflict("Split " + nodeId + " gives out " + plain(assigned) + " but has " + plain(total) + ".");
        }
        List<Decision> decisions = new ArrayList<>();
        for (Edge edge : edges) {
            BigDecimal amount = amounts.get(edge.id());
            if (amount.signum() == 0) {
                decisions.add(new Decision(edge.id(), edge.target(), false, "split_empty", amount));
                continue;
            }
            JsonNode share = withQuantity(output, amount);
            decisions.add(transfer(edge, share, values(share), "split", amount));
        }
        return decisions;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /**
     * A connection's transfer (GR-03, GR-07, GR-08) and its loss (docs/architecture/adr/ADR-009-connection-loss-rate.md
     * decisions 3-7): the capacity, the unit and the output port see what is sent, the input port what arrives. When a
     * positive quantity is all lost to rounding, nothing goes on.
     */
    private Decision transfer(Edge edge, JsonNode sent, Map<String, Object> sentValues, String reason, BigDecimal share) {
        Loss loss = loss(edge, sentValues);
        if (loss != null && loss.arrived().signum() == 0) {
            requireTransfer(edge, sent, sentValues, null, null);
            return new Decision(edge.id(), edge.target(), false, "loss_empty", loss.arrived(), loss);
        }
        JsonNode arriving = loss == null ? sent : withQuantity(sent, loss.arrived());
        requireTransfer(edge, sent, sentValues, arriving, loss == null ? sentValues : values(arriving));
        return new Decision(edge.id(), edge.target(), true, reason, loss == null ? share : loss.arrived(), loss);
    }

    /** A connection's loss on a positive quantity, what arrives rounded to 4 decimals HALF_UP (ADR-009 decision 3). */
    private static Loss loss(Edge edge, Map<String, Object> values) {
        if (edge.lossRate() == null || !(values.get("quantity") instanceof BigDecimal sent) || sent.signum() <= 0) {
            return null;
        }
        BigDecimal arrived = sent.multiply(BigDecimal.ONE.subtract(edge.lossRate())).setScale(4, RoundingMode.HALF_UP);
        // A quantity with more than 4 decimals can round up past itself; no more arrives than was sent.
        if (arrived.compareTo(sent) > 0) {
            arrived = sent;
        }
        return new Loss(sent, edge.lossRate(), sent.subtract(arrived), arrived);
    }

    /**
     * What a connection's transfer must pass: its capacity and unit, and both ports' schema and rule (GR-04). The input
     * port sees what arrives; nothing arriving leaves it unchecked.
     */
    private void requireTransfer(Edge edge, JsonNode output, Map<String, Object> values, JsonNode arriving,
        Map<String, Object> arrivingValues) {
        if (edge.capacity() != null) {
            Object quantity = values.get("quantity");
            if (!(quantity instanceof BigDecimal amount)) {
                throw conflict("Connection " + edge.id() + " requires outputSnapshot.quantity for capacity.");
            }
            if (amount.compareTo(edge.capacity()) > 0) {
                throw conflict("Connection " + edge.id() + " capacity exceeded.");
            }
        }
        Object actualUnit = values.get("unit");
        if (edge.unit() != null && actualUnit != null && !edge.unit().equals(actualUnit)) {
            throw conflict("Connection " + edge.id() + " unit does not match outputSnapshot.unit.");
        }
        requirePortSchema(edge.fromIoId(), output);
        if (arriving != null) requirePortSchema(edge.toIoId(), arriving);
        requirePortRule(edge.fromIoId(), values);
        if (arriving != null) requirePortRule(edge.toIoId(), arrivingValues);
    }

    private void requirePortRule(String portId, Map<String, Object> values) {
        if (portId == null) return;
        ConditionExpression rule = portRules.get(portId);
        if (rule != null && !rule.evaluate(values)) {
            throw conflict("Port " + portId + " validationRule did not pass.");
        }
    }

    private void requirePortSchema(String portId, JsonNode output) {
        if (portId == null) return;
        PortSchema schema = portSchemas.get(portId);
        if (schema == null) return;
        JsonNode attrs = output.path("attrs");
        if (!attrs.isMissingNode() && !attrs.isObject()) {
            throw conflict("Port " + portId + " outputSnapshot.attrs must be an object.");
        }
        for (String name : schema.required()) {
            if (!attrs.hasNonNull(name)) {
                throw conflict("Port " + portId + " requires outputSnapshot.attrs." + name + ".");
            }
        }
        for (Map.Entry<String, String> property : schema.properties().entrySet()) {
            JsonNode value = attrs.get(property.getKey());
            if (value == null) continue;
            boolean valid = switch (property.getValue()) {
                case "string" -> value.isTextual();
                case "number" -> value.isNumber();
                case "boolean" -> value.isBoolean();
                default -> false;
            };
            if (!valid) {
                throw conflict("Port " + portId + " outputSnapshot.attrs." + property.getKey()
                    + " must be " + property.getValue() + ".");
            }
        }
    }

    private static Map<String, Object> values(JsonNode output) {
        Map<String, Object> values = new HashMap<>();
        for (String field : List.of("quantity", "unit", "item")) {
            JsonNode node = output.get(field);
            if (node != null) values.put(field, value(node));
        }
        JsonNode attrs = output.path("attrs");
        if (attrs.isObject()) {
            Map<String, Object> attributes = new HashMap<>();
            attrs.fields().forEachRemaining(entry -> attributes.put(entry.getKey(), value(entry.getValue())));
            values.put("attrs", attributes);
        }
        return values;
    }

    private static Object value(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        return null;
    }

    private static NodePolicy policy(JsonNode node, String processId) {
        Integer timeout = bounded(node, "timeoutSeconds", 1, 604_800, processId);
        Integer limit = bounded(node, "retryLimit", 0, 10, processId);
        Integer delay = bounded(node, "retryDelaySeconds", 0, 86_400, processId);
        String backoff = textOrNull(node.path("retryBackoff"));
        if (backoff != null && !"fixed".equals(backoff) && !"exponential".equals(backoff)) {
            throw conflict("Published revision has an invalid node policy: " + processId);
        }
        Integer cap = bounded(node, "maxRetryDelaySeconds", 0, 604_800, processId);
        Integer concurrency = bounded(node, "concurrencyLimit", 1, 1000, processId);
        return new NodePolicy(timeout, limit == null ? NodePolicy.DEFAULT.retryLimit() : limit, delay == null ? 0 : delay,
            "exponential".equals(backoff), cap, concurrency);
    }

    private static Integer bounded(JsonNode node, String field, int min, int max, String processId) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < min || value.intValue() > max) {
            throw conflict("Published revision has an invalid node policy: " + processId);
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String field) {
        String value = textOrNull(node.path(field));
        if (value == null) throw conflict("Published revision has an invalid " + field + ".");
        return value;
    }

    private static ConditionExpression compilePublished(String expression, String field) {
        try {
            return ConditionExpression.compile(expression);
        } catch (BusinessException exception) {
            throw conflict("Published revision has invalid " + field + ".");
        }
    }

    private static void requirePortReference(Map<String, PortRef> portRefs, String portId,
        String processId, String direction, String connectionId) {
        if (portId == null) return;
        PortRef port = portRefs.get(portId);
        if (port == null || !processId.equals(port.processId()) || !direction.equals(port.direction())) {
            throw conflict("Published revision connection " + connectionId
                + " has an invalid " + ("output".equals(direction) ? "fromIoId" : "toIoId") + ".");
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.textValue().isBlank() ? node.textValue() : null;
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
