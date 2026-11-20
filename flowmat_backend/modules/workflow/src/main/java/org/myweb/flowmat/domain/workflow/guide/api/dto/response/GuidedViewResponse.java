package org.myweb.flowmat.domain.workflow.guide.api.dto.response;

import java.util.List;

/**
 * A chapter as a reader sees it (ADR-037).
 *
 * @param focusProcessIds the processes it shows that the workflow still has, in reading order; deleted ones are left out
 */
public record GuidedViewResponse(String guidedViewId, String workflowId, int sortOrder, String label, String note,
                                 List<String> focusProcessIds, int version) {
}
