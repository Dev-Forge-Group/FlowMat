package org.myweb.flowmat.domain.connector.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;

public record JsonMappingPreviewResponse(int version, JsonNode result) {
    @com.fasterxml.jackson.annotation.JsonProperty("resultJson")
    public String resultJson() {
        return result == null ? "null" : result.toString();
    }

    @Override
    public String toString() {
        return "JsonMappingPreviewResponse[version=" + version + "]";
    }
}
