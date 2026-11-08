package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record ProcessExecutionBindingResponse(String processId, String profileVersionId, String operationId,
    String outputPortId, JsonNode requestMapping, JsonNode responseMapping, long version) {
    /** Editors use these JSON strings so JavaScript numbers cannot round-trip decimal literals through a double. */
    @JsonProperty("requestMappingJson")
    public String requestMappingJson() {
        return requestMapping == null ? null : requestMapping.toString();
    }

    @JsonProperty("responseMappingJson")
    public String responseMappingJson() {
        return responseMapping == null ? null : responseMapping.toString();
    }
}
