package org.myweb.flowmat.domain.workflow.api.dto.response;

/**
 * A node's execution policy as saved; null values use the default behavior: no time limit, 3 retries, no delay, fixed,
 * no cap and no concurrency limit (docs/domain/flow-run-execution-policy.md EP1). It applies to runs of revisions
 * published after it was saved (EP3).
 */
public record ProcessExecutionPolicyResponse(
    String processId,
    Integer timeoutSeconds,
    Integer retryLimit,
    Integer retryDelaySeconds,
    String retryBackoff,
    Integer maxRetryDelaySeconds,
    Integer concurrencyLimit,
    long version
) {
}
