package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

public record FlowRunFinishRequest(
    @JsonDeserialize(using = FlowRunOutputPayloadDeserializer.class) JsonNode outputPayload
) {
}
