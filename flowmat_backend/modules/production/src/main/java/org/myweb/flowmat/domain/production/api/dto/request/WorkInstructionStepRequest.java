package org.myweb.flowmat.domain.production.api.dto.request;

/**
 * A step; required defaults to true, recordsValue to false. {@code valueLabel} names the value, such as "Oven °C";
 * {@code valueMin} and {@code valueMax} are its limits (R7), only for a step that records a value. {@code standardId}
 * names an inspection standard of the product whose limits the step follows (W13); its limits are then left out or the same.
 * {@code processId} names the process the step belongs to (docs/architecture/adr/ADR-011-production-steps.md decision 6);
 * left out, it belongs to the whole run.
 */
public record WorkInstructionStepRequest(
    String text,
    Boolean required,
    Boolean recordsValue,
    String valueLabel,
    java.math.BigDecimal valueMin,
    java.math.BigDecimal valueMax,
    String standardId,
    String processId
) {

    /** A step for the whole run, as callers wrote it before V91. */
    public WorkInstructionStepRequest(String text, Boolean required, Boolean recordsValue, String valueLabel,
                                      java.math.BigDecimal valueMin, java.math.BigDecimal valueMax, String standardId) {
        this(text, required, recordsValue, valueLabel, valueMin, valueMax, standardId, null);
    }
}
