package org.myweb.flowmat.domain.production.api.dto.request;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Completes a process step (docs/architecture/adr/ADR-011-production-steps.md decision 3). Left out, {@code outputSnapshot}
 * is what was recorded for the process: the quantity, unit and item of its outputs when they are one item in one unit.
 */
public record ProductionRunStepCompleteRequest(JsonNode outputSnapshot) {
}
