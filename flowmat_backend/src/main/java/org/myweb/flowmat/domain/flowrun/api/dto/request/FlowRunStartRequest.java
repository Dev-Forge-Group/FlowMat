package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;
import jakarta.validation.constraints.NotBlank;

public record FlowRunStartRequest(
    @NotBlank String workflowId,
    @NotBlank String workflowRevisionId,
    @NotBlank String runType,
    @JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode inputPayload
) {
}
