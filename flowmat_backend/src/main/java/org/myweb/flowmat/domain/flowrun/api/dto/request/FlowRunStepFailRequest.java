package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.myweb.flowmat.global.util.WholeNumberDeserializer;

public record FlowRunStepFailRequest(
    @NotBlank @Size(max = 100) String errorCode,
    @Size(max = 4000) String errorMessage,
    /** The attempt this report is for; a different running attempt is a late report, 409 (docs/domain/flow-run-execution-policy.md EP8). */
    @JsonDeserialize(using = WholeNumberDeserializer.class) Integer attemptNo
) {
}
