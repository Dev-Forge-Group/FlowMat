package org.myweb.flowmat.domain.flowrun.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;

/** {@code attemptNo}: the attempt this report is for; a different running attempt is a late report, 409 (docs/domain/flow-run-execution-policy.md EP8). */
public record FlowRunStepCompleteRequest(@JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode outputSnapshot, Integer attemptNo) {
}
