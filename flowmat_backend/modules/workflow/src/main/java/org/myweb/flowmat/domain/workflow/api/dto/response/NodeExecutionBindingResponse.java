package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

/** A binding fixed in a published revision; the pinned profile version is immutable. */
public record NodeExecutionBindingResponse(String processId, String profileVersionId, String operationId,
    String outputPortId, JsonNode requestMapping, JsonNode responseMapping) {
}
