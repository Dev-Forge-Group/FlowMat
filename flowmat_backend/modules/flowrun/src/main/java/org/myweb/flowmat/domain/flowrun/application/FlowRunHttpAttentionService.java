package org.myweb.flowmat.domain.flowrun.application;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.api.dto.response.FlowRunHttpAttentionPageResponse;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunHttpExecutionRepository;
import org.myweb.flowmat.domain.flowrun.repository.FlowRunHttpExecutionRepository.AttentionRow;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only review. Query classification does not change whether an HTTP transmission can be retried. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FlowRunHttpAttentionService {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_CURSOR_LENGTH = 512;
    private static final OffsetDateTime NO_CURSOR_TIME = OffsetDateTime.parse("1970-01-01T00:00:00Z");
    private static final Set<String> STATES = Set.of("ALL", "PENDING", "OUTCOME_UNKNOWN", "APPLY_FAILED", "LATE");

    private final ProjectAccess access;
    private final FlowRunHttpExecutionRepository executions;

    public FlowRunHttpAttentionPageResponse search(String projectId, String limit, String cursor, String state) {
        access.requireProjectReadAccess(projectId);
        int size = parseLimit(limit);
        String selectedState = state == null ? "ALL" : state;
        if (!STATES.contains(selectedState)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "state must be ALL, PENDING, OUTCOME_UNKNOWN, APPLY_FAILED or LATE.");
        }
        Cursor after = Cursor.decode(cursor);
        List<AttentionRow> rows = executions.findAttention(projectId, selectedState, after != null,
            after == null ? NO_CURSOR_TIME : after.startedAt(), after == null ? "" : after.executionId(),
            PageRequest.of(0, size + 1));
        boolean more = rows.size() > size;
        List<AttentionRow> page = more ? rows.subList(0, size) : rows;
        AttentionRow last = page.isEmpty() ? null : page.get(page.size() - 1);
        String next = more && last != null ? new Cursor(last.getStartedAt(), last.getExecutionId()).encode() : null;
        return new FlowRunHttpAttentionPageResponse(page.stream().map(FlowRunHttpAttentionService::item).toList(), next);
    }

    private static int parseLimit(String value) {
        if (value == null) return DEFAULT_LIMIT;
        if (!value.matches("[0-9]{1,3}")) throw invalidLimit();
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > MAX_LIMIT) throw invalidLimit();
        return parsed;
    }

    private static BusinessException invalidLimit() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "limit must be an integer between 1 and " + MAX_LIMIT + ".");
    }

    private static FlowRunHttpAttentionPageResponse.Item item(AttentionRow row) {
        return new FlowRunHttpAttentionPageResponse.Item(row.getExecutionId(), row.getFlowRunId(), row.getWorkflowId(),
            row.getRunStatus(), row.getStepId(), row.getNodeId(), row.getStepStatus(), row.getAttemptId(),
            row.getOperationId(), row.getTransportStatus(), row.getApplyStatus(), row.getHttpStatus(),
            row.getErrorCode(), row.getStartedAt(), row.getCompletedAt());
    }

    /** Base64url of the last timestamp and ID; every query still binds the requested project's current access. */
    private record Cursor(OffsetDateTime startedAt, String executionId) {
        String encode() {
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((startedAt + "|" + executionId).getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String value) {
            if (value == null) return null;
            try {
                if (value.isEmpty() || value.length() > MAX_CURSOR_LENGTH || !value.matches("[A-Za-z0-9_-]+")) {
                    throw new IllegalArgumentException();
                }
                byte[] bytes = Base64.getUrlDecoder().decode(value);
                if (!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
                    throw new IllegalArgumentException();
                }
                String raw = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                int bar = raw.indexOf('|');
                if (bar < 1 || bar != raw.lastIndexOf('|')) throw new IllegalArgumentException();
                String id = raw.substring(bar + 1);
                if (!id.matches("[A-Za-z0-9_-]{1,50}")) throw new IllegalArgumentException();
                OffsetDateTime time = OffsetDateTime.parse(raw.substring(0, bar)).withOffsetSameInstant(ZoneOffset.UTC);
                // Persisted timestamps use microseconds; reject extreme or rounded boundaries before reaching JDBC.
                if (time.getYear() < 1 || time.getYear() > 9999 || time.getNano() % 1000 != 0) {
                    throw new IllegalArgumentException();
                }
                return new Cursor(time, id);
            } catch (IllegalArgumentException | DateTimeException | CharacterCodingException invalid) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "cursor is not valid; start from the first page.");
            }
        }
    }
}
