package org.myweb.flowmat.domain.workflow.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessExecutionPolicyRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessExecutionPolicyResponse;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.repository.ProcessRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A node's execution policy (docs/architecture/adr/ADR-004-flow-run-execution-policy.md, docs/domain/flow-run-execution-policy.md
 * EP1-EP2). Saved on the node; a run uses the copy fixed in its published revision (EP3).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessExecutionPolicyService {

    private static final Set<String> BACKOFFS = Set.of("fixed", "exponential");

    private final WorkflowAccessService workflowAccess;
    private final ProcessRepository processes;
    private final EntityManager entities;

    public ProcessExecutionPolicyResponse get(String processId) {
        Process process = workflowAccess.requireProcessReadAccess(processId);
        // A node of a deleted workflow has no policy to show (EP2).
        workflowAccess.requireWorkflowReadAccess(process.getWorkflowId());
        return response(process);
    }

    /** Under the node's row lock with the loaded version; all nulls go back to the default behavior. */
    @Transactional
    public ProcessExecutionPolicyResponse set(String processId, ProcessExecutionPolicyRequest request) {
        Process process = workflowAccess.requireProcessWriteAccess(processId);
        entities.refresh(process, LockModeType.PESSIMISTIC_WRITE);
        if (!"N".equals(process.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        // Checked after the node lock, so a workflow deleted while this waited is refused (EP2).
        workflowAccess.requireWorkflowWriteAccess(process.getWorkflowId());
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "expectedVersion is required, a nonnegative integer.");
        }
        String backoff = request.retryBackoff() == null || request.retryBackoff().isBlank()
            ? null : request.retryBackoff().trim().toLowerCase(Locale.ROOT);
        validate(request, backoff);
        long version = process.getExecutionPolicyVersion();
        if (version != request.expectedVersion()) {
            // Sending the acknowledged change again after a lost response is harmless.
            if (version > 0 && request.expectedVersion() == version - 1 && same(process, request, backoff)) return response(process);
            throw new BusinessException(ErrorCode.CONFLICT,
                "expectedVersion changed; reload the execution policy before saving a different one.");
        }
        if (version == Long.MAX_VALUE) throw new BusinessException(ErrorCode.CONFLICT, "expectedVersion has reached its limit.");
        process.setTimeoutSeconds(request.timeoutSeconds());
        process.setRetryLimit(request.retryLimit());
        process.setRetryDelaySeconds(request.retryDelaySeconds());
        process.setRetryBackoff(backoff);
        process.setMaxRetryDelaySeconds(request.maxRetryDelaySeconds());
        process.setConcurrencyLimit(request.concurrencyLimit());
        process.setExecutionPolicyVersion(version + 1);
        return response(processes.save(process));
    }

    private static void validate(ProcessExecutionPolicyRequest request, String backoff) {
        range(request.timeoutSeconds(), 1, 604_800, "timeoutSeconds");
        range(request.retryLimit(), 0, 10, "retryLimit");
        range(request.retryDelaySeconds(), 0, 86_400, "retryDelaySeconds");
        if (backoff != null && !BACKOFFS.contains(backoff)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "retryBackoff is fixed or exponential.");
        }
        if (request.maxRetryDelaySeconds() != null) {
            if (!"exponential".equals(backoff)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "maxRetryDelaySeconds applies to exponential backoff only.");
            }
            int floor = request.retryDelaySeconds() == null ? 0 : request.retryDelaySeconds();
            if (request.maxRetryDelaySeconds() < floor || request.maxRetryDelaySeconds() > 604_800) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "maxRetryDelaySeconds must be between retryDelaySeconds and 604800.");
            }
        }
        range(request.concurrencyLimit(), 1, 1000, "concurrencyLimit");
    }

    private static void range(Integer value, int min, int max, String field) {
        if (value != null && (value < min || value > max)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " must be between " + min + " and " + max + ".");
        }
    }

    private static boolean same(Process process, ProcessExecutionPolicyRequest request, String backoff) {
        return Objects.equals(process.getTimeoutSeconds(), request.timeoutSeconds())
            && Objects.equals(process.getRetryLimit(), request.retryLimit())
            && Objects.equals(process.getRetryDelaySeconds(), request.retryDelaySeconds())
            && Objects.equals(process.getRetryBackoff(), backoff)
            && Objects.equals(process.getMaxRetryDelaySeconds(), request.maxRetryDelaySeconds())
            && Objects.equals(process.getConcurrencyLimit(), request.concurrencyLimit());
    }

    private static ProcessExecutionPolicyResponse response(Process process) {
        return new ProcessExecutionPolicyResponse(process.getProcessId(), process.getTimeoutSeconds(), process.getRetryLimit(),
            process.getRetryDelaySeconds(), process.getRetryBackoff(), process.getMaxRetryDelaySeconds(),
            process.getConcurrencyLimit(), process.getExecutionPolicyVersion());
    }
}
