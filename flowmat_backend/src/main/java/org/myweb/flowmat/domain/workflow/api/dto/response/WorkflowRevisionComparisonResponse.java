package org.myweb.flowmat.domain.workflow.api.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** A page of snapshot differences, not an execution-equivalence judgment or an applicable patch. */
public record WorkflowRevisionComparisonResponse(
    String workflowId, Revision fromRevision, Revision toRevision, int totalChanges, List<Change> changes, Integer nextOffset
) {
    public WorkflowRevisionComparisonResponse {
        changes = List.copyOf(changes);
    }

    public record Revision(String revisionId, int revisionNo, int schemaVersion, String status) {}

    /**
     * section is a top-level snapshot field. entityId identifies a member of an ID-based collection, otherwise null.
     * path is a JSON pointer relative to that member/section; empty means the whole value. kind is added, removed or
     * modified, distinguishing an absent field from an explicit JSON null. An added/removed member counts as one change.
     */
    public record Change(String section, String entityId, String path, String kind, JsonNode before, JsonNode after) {}
}
