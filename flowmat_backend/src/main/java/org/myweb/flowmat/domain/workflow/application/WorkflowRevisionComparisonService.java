package org.myweb.flowmat.domain.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse.Revision;
import org.myweb.flowmat.domain.workflow.domain.entity.WorkflowRevision;
import org.myweb.flowmat.domain.workflow.repository.WorkflowRevisionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class WorkflowRevisionComparisonService {
    private final WorkflowRevisionRepository repository;
    private final WorkflowAccessService access;
    private final ObjectReader reader;

    public WorkflowRevisionComparisonService(WorkflowRevisionRepository repository, WorkflowAccessService access, ObjectMapper mapper) {
        this.repository = repository;
        this.access = access;
        // Derived, immutable reader: comparing decimals must not round them to double or mutate the shared mapper.
        this.reader = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    public WorkflowRevisionComparisonResponse compare(String workflowId, String fromId, String toId, String offset, String limit) {
        access.requireWorkflowReadAccess(workflowId);
        identifier(fromId, "fromRevisionId");
        identifier(toId, "toRevisionId");
        int start = number(offset, "offset", 0, 0, Integer.MAX_VALUE);
        int size = number(limit, "limit", 100, 1, 500);
        WorkflowRevision before = find(workflowId, fromId);
        WorkflowRevision after = fromId.equals(toId) ? before : find(workflowId, toId);
        JsonNode left = snapshot(before);
        JsonNode right = before == after ? left : snapshot(after);
        var result = WorkflowRevisionDiffer.compare(left, right, start, size);
        return new WorkflowRevisionComparisonResponse(workflowId, reference(before), reference(after),
            result.totalChanges(), result.changes(), result.nextOffset());
    }

    private WorkflowRevision find(String workflowId, String id) {
        return repository.findByWorkflowRevisionIdAndWorkflowId(id, workflowId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private JsonNode snapshot(WorkflowRevision row) {
        try {
            return reader.readTree(row.getSnapshotJson());
        } catch (JsonProcessingException exception) {
            throw new BusinessException(ErrorCode.CONFLICT, "Stored revision snapshot cannot be compared.");
        }
    }

    private static Revision reference(WorkflowRevision row) {
        return new Revision(row.getWorkflowRevisionId(), row.getRevisionNo(), row.getSchemaVersion(), row.getStatus());
    }

    private static void identifier(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 50) throw bad(field + " must contain between 1 and 50 characters.");
        StorableText.require(value, field);
    }

    private static int number(String value, String field, int fallback, int minimum, int maximum) {
        try {
            int parsed = value == null ? fallback : Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // The same field-specific 400 covers malformed and out-of-range integers.
        }
        throw bad(field + " must be an integer between " + minimum + " and " + maximum + ".");
    }

    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
}
