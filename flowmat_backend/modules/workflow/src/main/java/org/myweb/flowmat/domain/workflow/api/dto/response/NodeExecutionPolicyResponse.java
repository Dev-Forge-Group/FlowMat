package org.myweb.flowmat.domain.workflow.api.dto.response;

/** A node's execution policy fixed in a published revision; only nodes with a policy are listed (docs/domain/flow-run-execution-policy.md EP3). */
public record NodeExecutionPolicyResponse(
    String processId,
    Integer timeoutSeconds,
    Integer retryLimit,
    Integer retryDelaySeconds,
    String retryBackoff,
    Integer maxRetryDelaySeconds,
    Integer concurrencyLimit
) {
}
