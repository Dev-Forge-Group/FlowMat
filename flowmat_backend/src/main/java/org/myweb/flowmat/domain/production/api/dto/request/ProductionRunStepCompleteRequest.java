package org.myweb.flowmat.domain.production.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.myweb.flowmat.global.util.ExactJsonNodeDeserializer;

/**
 * Completes a process step (docs/architecture/adr/ADR-011-production-steps.md decision 3). Left out, {@code outputSnapshot}
 * is what was recorded for the process: the quantity, unit and item of its outputs when they are one item in one unit. Its
 * decimals stay exact, as in a flow run step's (docs/domain/flow-run.md F16).
 */
public record ProductionRunStepCompleteRequest(@JsonDeserialize(using = ExactJsonNodeDeserializer.class) JsonNode outputSnapshot) {
}
