package org.myweb.flowmat.domain.production.api.dto.request;

/**
 * Confirms a step; {@code value} is required for steps that record one. {@code result} (pass or fail) is for a step that
 * follows an inspection standard without limits, where a person decides (docs/domain/work-instruction.md R10).
 */
public record RunInstructionCheckRequest(
    String value,
    String note,
    String result
) {
}
