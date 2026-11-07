package org.myweb.flowmat.domain.workflow.api.dto.response;

import java.util.List;
import org.myweb.flowmat.domain.workflow.collab.dto.GraphChangeMessage;

public record WorkflowGraphChangesResponse(
    long currentSeq,
    boolean resetRequired,
    List<GraphChangeMessage> changes
) {
}
