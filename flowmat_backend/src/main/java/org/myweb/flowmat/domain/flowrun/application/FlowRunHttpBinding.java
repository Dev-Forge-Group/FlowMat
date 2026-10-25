package org.myweb.flowmat.domain.flowrun.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;

/** Reads immutable binding data exclusively from the run's published revision. */
record FlowRunHttpBinding(String profileVersionId, String operationId, String outputPortId,
    JsonNode requestMapping, JsonNode responseMapping) {
    @Override public String toString() {
        return "FlowRunHttpBinding[profileVersionId=" + profileVersionId + ", operationId=" + operationId + "]";
    }
    static FlowRunHttpBinding read(JsonNode snapshot, String processId) {
        if (snapshot.path("schemaVersion").asInt() != 4 || !snapshot.path("nodeBindings").isArray()) {
            throw invalid();
        }
        JsonNode process = null;
        for (JsonNode value : snapshot.path("processes")) {
            if (processId.equals(value.path("processId").asText())) process = value;
        }
        if (process == null || Set.of("exclusive", "join", "split")
            .contains(process.path("nodeType").asText().toLowerCase(java.util.Locale.ROOT))) throw invalid();
        JsonNode selected = null;
        for (JsonNode binding : snapshot.path("nodeBindings")) {
            if (!processId.equals(binding.path("processId").asText())) continue;
            if (selected != null) throw invalid();
            selected = binding;
        }
        if (selected == null) throw invalid();
        String profile = text(selected, "profileVersionId");
        String operation = text(selected, "operationId");
        String port = text(selected, "outputPortId");
        boolean ownedOutput = false;
        for (JsonNode value : snapshot.path("processIos")) {
            if (port.equals(value.path("processIoId").asText()) && processId.equals(value.path("processId").asText())
                && "output".equals(value.path("direction").asText())) ownedOutput = true;
        }
        if (!ownedOutput || !selected.path("requestMapping").isObject() || !selected.path("responseMapping").isObject()) {
            throw invalid();
        }
        return new FlowRunHttpBinding(profile, operation, port, selected.get("requestMapping"), selected.get("responseMapping"));
    }
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) throw invalid();
        return value.textValue();
    }
    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.CONFLICT, "Step has no valid published HTTP binding.");
    }
}
