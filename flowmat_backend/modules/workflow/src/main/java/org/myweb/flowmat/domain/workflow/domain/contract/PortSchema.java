package org.myweb.flowmat.domain.workflow.domain.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** The small interoperable subset of schemaJson supported by workflow ports. */
public record PortSchema(Map<String, String> properties, Set<String> required) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> TYPES = Set.of("string", "number", "boolean");

    public PortSchema {
        properties = Map.copyOf(properties);
        required = Set.copyOf(required);
    }

    public static PortSchema parse(JsonNode json) {
        if (json == null || json.isNull()) return null;
        if (!json.isObject() || !json.path("type").isTextual()
            || !"object".equals(json.path("type").asText())) {
            throw bad("schemaJson.type must be object.");
        }
        requireStorableJsonText(json);
        JsonNode propertyNodes = json.get("properties");
        if (propertyNodes != null && !propertyNodes.isObject()) {
            throw bad("schemaJson.properties must be an object.");
        }
        Map<String, String> properties = new HashMap<>();
        if (propertyNodes != null) {
            propertyNodes.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw bad("schemaJson.properties has invalid name '" + name + "'.");
                }
                JsonNode property = entry.getValue();
                String type = property != null && property.isObject() && property.path("type").isTextual()
                    ? property.path("type").asText() : "";
                if (!TYPES.contains(type)) {
                    throw bad("schemaJson.properties." + name + ".type must be string, number, or boolean.");
                }
                properties.put(name, type);
            });
        }
        JsonNode requiredNodes = json.get("required");
        if (requiredNodes != null && !requiredNodes.isArray()) {
            throw bad("schemaJson.required must be an array.");
        }
        Set<String> required = new HashSet<>();
        if (requiredNodes != null) {
            for (JsonNode name : requiredNodes) {
                if (!name.isTextual() || !properties.containsKey(name.asText())) {
                    throw bad("schemaJson.required must name a property in schemaJson.properties.");
                }
                required.add(name.asText());
            }
        }
        return new PortSchema(properties, required);
    }

    /** Unknown schema metadata is also stored in jsonb, so its text and keys must be storable. */
    private static void requireStorableJsonText(JsonNode json) {
        ArrayDeque<JsonNode> pending = new ArrayDeque<>();
        pending.add(json);
        while (!pending.isEmpty()) {
            JsonNode node = pending.removeLast();
            if (node.isTextual()) {
                requireStorableText(node.textValue());
            } else if (node.isObject()) {
                node.fields().forEachRemaining(entry -> {
                    requireStorableText(entry.getKey());
                    pending.add(entry.getValue());
                });
            } else if (node.isArray()) {
                node.forEach(pending::add);
            }
        }
    }

    private static void requireStorableText(String text) {
        WorkflowText.requireStorable(text, "schemaJson");
    }

    public static PortSchema parseStored(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return parse(MAPPER.readTree(json));
        } catch (JsonProcessingException exception) {
            throw bad("Stored schemaJson is not valid JSON.");
        }
    }

    public static void requireCompatible(PortSchema output, PortSchema input) {
        if (output == null || input == null) return;
        for (String name : input.required()) {
            String targetType = input.properties().get(name);
            if (!targetType.equals(output.properties().get(name))) {
                throw bad("Target port needs " + name + " (" + targetType + ").");
            }
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
