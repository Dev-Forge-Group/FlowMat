package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.domain.flowrun.application.FlowRunGraph.Edge;
import org.myweb.flowmat.domain.flowrun.application.FlowRunGraph.NodePolicy;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Reads and validates only the graph stored in a published revision. */
final class PublishedFlowRunGraphReader {
    private static final Set<String> POLICIES = Set.of("stop", "skip", "retry");

    private PublishedFlowRunGraphReader() {}

    private record PortRef(String processId, String direction) {}

    static FlowRunGraph read(JsonNode snapshot) {
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
            // A revision published before ADR-022 has no basis: its delays count all time.
            boolean workingDelay = "working".equals(textOrNull(connection.path("delayBasis")));
            Edge edge = new Edge(id, target, fromIoId, toIoId, condition, capacity, unit, policy, priority, defaultRoute,
                byId.size(), splitKind, splitValue, lossRate, delaySeconds, workingDelay);
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
