package org.myweb.flowmat.domain.workflow.api.dto.request;

/**
 * A node's execution policy; a null value uses the default behavior (docs/domain/flow-run-execution-policy.md EP1-EP2).
 *
 * @param expectedVersion the policy version this change was made from
 */
public record ProcessExecutionPolicyRequest(
    Integer timeoutSeconds,
    Integer retryLimit,
    Integer retryDelaySeconds,
    String retryBackoff,
    Integer maxRetryDelaySeconds,
    Integer concurrencyLimit,
    Long expectedVersion
) {
}
