package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;

public record FlowRunStepCreateRequest(@NotBlank String nodeId, @JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode inputSnapshot,
    String scheduledAt) {
}
