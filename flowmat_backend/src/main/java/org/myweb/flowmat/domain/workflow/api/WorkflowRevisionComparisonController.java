package org.myweb.flowmat.domain.workflow.api;

import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.workflow.api.dto.response.WorkflowRevisionComparisonResponse;
import org.myweb.flowmat.domain.workflow.application.WorkflowRevisionComparisonService;
import org.myweb.flowmat.global.response.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compares frozen snapshots in the requested direction, including retired revisions, within existing workflow read
 * access. Results visit sections, entity IDs and object fields in sorted order, and nested arrays by index. Known graph
 * collections ignore ordering. Numeric scale alone is not a change, and absent/null legacy graph collections are empty. All stored fields,
 * including version metadata and future sections, participate. This is not a merge, patch, restore or execution check.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/workflows/{workflowId}/revisions/compare")
public class WorkflowRevisionComparisonController {
    private final WorkflowRevisionComparisonService service;

    @GetMapping
    public ApiResponse<WorkflowRevisionComparisonResponse> compare(
        @PathVariable("workflowId") String workflowId,
        @RequestParam("fromRevisionId") String fromRevisionId,
        @RequestParam("toRevisionId") String toRevisionId,
        @RequestParam(value = "offset", required = false) String offset,
        @RequestParam(value = "limit", required = false) String limit
    ) {
        return ApiResponse.ok(service.compare(workflowId, fromRevisionId, toRevisionId, offset, limit));
    }
}
