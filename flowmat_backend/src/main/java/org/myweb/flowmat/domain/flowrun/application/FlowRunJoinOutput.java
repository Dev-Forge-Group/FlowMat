package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/** Builds one Join's output from arrivals in their supplied order (ADR-007 decision 7). */
final class FlowRunJoinOutput {

    private final ObjectNode merged;
    private final ArrayNode arrivals;
    private BigDecimal total = BigDecimal.ZERO;
    private boolean summable = true;
    private boolean firstUnit = true;
    private String unit;
    private JsonNode item;
    private boolean sameItem = true;

    FlowRunJoinOutput(ObjectMapper objectMapper) {
        merged = objectMapper.createObjectNode();
        arrivals = merged.putArray("arrivals");
    }

    void add(String connectionId, String sourceStepId, JsonNode output) {
        ObjectNode arrival = arrivals.addObject();
        arrival.put("connectionId", connectionId);
        arrival.put("sourceStepId", sourceStepId);
        for (String field : List.of("quantity", "unit", "item", "attrs")) {
            if (output.hasNonNull(field)) {
                arrival.set(field, output.get(field));
            }
        }
        JsonNode quantity = output.get("quantity");
        if (quantity == null || !quantity.isNumber()) {
            summable = false;
        } else {
            total = total.add(quantity.decimalValue());
        }
        String arrivalUnit = output.hasNonNull("unit") ? output.get("unit").asText() : null;
        if (firstUnit) {
            unit = arrivalUnit;
            firstUnit = false;
        } else if (!Objects.equals(unit, arrivalUnit)) {
            summable = false;
        }
        JsonNode arrivalItem = output.get("item");
        if (arrivalItem == null || arrivalItem.isNull()) {
            sameItem = false;
        } else if (item == null) {
            item = arrivalItem;
        } else if (!item.equals(arrivalItem)) {
            sameItem = false;
        }
    }

    JsonNode result() {
        if (arrivals.isEmpty()) {
            return merged;
        }
        if (summable) {
            merged.put("quantity", total);
            if (unit != null) {
                merged.put("unit", unit);
            }
        }
        if (sameItem && item != null) {
            merged.set("item", item);
        }
        return merged;
    }
}
