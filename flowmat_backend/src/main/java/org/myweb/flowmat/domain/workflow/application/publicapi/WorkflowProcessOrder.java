package org.myweb.flowmat.domain.workflow.application.publicapi;

import java.util.Map;
import java.util.Set;

/** The order of a workflow's processes for planning (docs/domain/worker-calendar.md LB34). The caller checks project access. */
public interface WorkflowProcessOrder {

    /**
     * For each process of the workflow that is not a gateway, the nearest processes before it along its live connections, passing
     * through gateways: the processes it waits for. The current definition, as a work order's step plan uses it.
     */
    Map<String, Set<String>> processPredecessors(String workflowId);
}
