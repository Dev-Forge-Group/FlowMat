package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import org.myweb.flowmat.domain.workflow.domain.contract.PortSchema;
import org.myweb.flowmat.domain.workflow.domain.expression.ConditionExpression;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Validates the published data contracts of a step's output and input ports. */
final class FlowRunPortContracts {
    private final Map<String, ConditionExpression> portRules;
    private final Map<String, PortSchema> portSchemas;

    FlowRunPortContracts(Map<String, ConditionExpression> portRules, Map<String, PortSchema> portSchemas) {
        this.portRules = portRules;
        this.portSchemas = portSchemas;
    }

    /** Validates the explicitly bound output even when the node has no outgoing connection. */
    static void requirePublishedOutput(JsonNode snapshot, String nodeId, String portId, JsonNode output) {
        JsonNode port = null;
        for (JsonNode candidate : snapshot.path("processIos")) {
            if (portId.equals(candidate.path("processIoId").asText()) && nodeId.equals(candidate.path("processId").asText())
                && "output".equals(candidate.path("direction").asText())) port = candidate;
        }
        if (port == null || output == null || !output.isObject()) {
            throw conflict("HTTP output must be an object for the published outputPortId.");
        }
        Map<String, PortSchema> schemas = new HashMap<>();
        Map<String, ConditionExpression> rules = new HashMap<>();
        if (port.hasNonNull("schemaJson")) schemas.put(portId, PortSchema.parse(port.get("schemaJson")));
        if (port.hasNonNull("validationRule") && !port.path("validationRule").asText().isBlank()) {
            rules.put(portId, ConditionExpression.compile(port.path("validationRule").asText()));
        }
        FlowRunPortContracts contracts = new FlowRunPortContracts(rules, schemas);
        contracts.requireSchema(portId, output);
        Map<String, Object> values = new HashMap<>();
        for (String name : List.of("quantity", "unit", "item")) {
            if (output.has(name)) values.put(name, scalar(output.get(name)));
        }
        Map<String, Object> attrs = new HashMap<>();
        if (output.path("attrs").isObject()) output.path("attrs").fields()
            .forEachRemaining(entry -> attrs.put(entry.getKey(), scalar(entry.getValue())));
        values.put("attrs", attrs);
        contracts.requireRule(portId, values);
    }

    private static Object scalar(JsonNode node) {
        if (node.isNumber()) return node.decimalValue();
        if (node.isTextual()) return node.textValue();
        if (node.isBoolean()) return node.booleanValue();
        return null;
    }

    void requireRule(String portId, Map<String, Object> values) {
        if (portId == null) return;
        ConditionExpression rule = portRules.get(portId);
        if (rule != null && !rule.evaluate(values)) {
            throw conflict("Port " + portId + " validationRule did not pass.");
        }
    }

    void requireSchema(String portId, JsonNode output) {
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

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
