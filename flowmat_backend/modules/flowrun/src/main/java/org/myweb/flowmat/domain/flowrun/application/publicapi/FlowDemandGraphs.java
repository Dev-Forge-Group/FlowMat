package org.myweb.flowmat.domain.flowrun.application.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Connection;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.ExecutionRequirement;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Node;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowDemand.Port;

/**
 * A published revision's snapshot as {@link FlowDemand} reads it (ADR-043 decision 2): processes, ports and connections as
 * runs read them (loss from schema version 2, as FlowRunGraph). A port's item is its {@code ref}; nodes' execution
 * requirements come from the snapshot's {@code processRequirements} (ADR-043 decision 4), none in revisions published before.
 */
public final class FlowDemandGraphs {

    private FlowDemandGraphs() {
    }

    public static FlowDemand.Graph fromSnapshot(JsonNode snapshot) {
        Map<String, List<ExecutionRequirement>> execution = new HashMap<>();
        for (JsonNode requirement : snapshot.path("processRequirements")) {
            execution.computeIfAbsent(text(requirement, "processId"), ignored -> new ArrayList<>()).add(new ExecutionRequirement(
                text(requirement, "kind"), text(requirement, "ref"), decimal(requirement, "amount"), text(requirement, "unit"),
                text(requirement, "per")));
        }
        List<Node> nodes = new ArrayList<>();
        for (JsonNode process : snapshot.path("processes")) {
            String id = text(process, "processId");
            nodes.add(new Node(id, text(process, "nodeType"), execution.getOrDefault(id, List.of())));
        }
        List<Port> ports = new ArrayList<>();
        for (JsonNode port : snapshot.path("processIos")) {
            ports.add(new Port(text(port, "processIoId"), text(port, "processId"), text(port, "direction"),
                decimal(port, "quantity"), text(port, "unit"), text(port, "resourceType"), text(port, "itemId"),
                text(port, "formula")));
        }
        boolean lossApplies = snapshot.path("schemaVersion").asInt(1) >= 2;
        List<Connection> connections = new ArrayList<>();
        for (JsonNode connection : snapshot.path("connections")) {
            BigDecimal loss = lossApplies ? decimal(connection, "lossRate") : null;
            JsonNode priority = connection.path("priority");
            connections.add(new Connection(text(connection, "connectionId"), text(connection, "fromProcessId"),
                text(connection, "toProcessId"), text(connection, "fromIoId"), text(connection, "toIoId"),
                text(connection, "conditionExpr"), decimal(connection, "capacity"),
                loss == null || loss.signum() == 0 ? null : loss, text(connection, "splitKind"), decimal(connection, "splitValue"),
                priority.isIntegralNumber() && priority.canConvertToInt() ? priority.intValue() : 0,
                connection.path("defaultRoute").asBoolean(false)));
        }
        return new FlowDemand.Graph(nodes, ports, connections);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.decimalValue() : null;
    }
}
