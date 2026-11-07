package org.myweb.flowmat.domain.production.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.flowrun.application.publicapi.FlowRunCommand;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps the common execution record in the same transaction as a revision-linked manufacturing run, through the flow run
 * public API (docs/architecture/adr/ADR-002-module-dependency.md). Runs without a workflow revision have no flow run.
 */
@Service
@RequiredArgsConstructor
public class ProductionFlowRunAdapter {

    private final FlowRunCommand flowRunCommand;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.MANDATORY)
    public void onStarted(ProductionRun productionRun) {
        if (productionRun.getWorkflowRevisionId() == null) {
            return;
        }
        flowRunCommand.startLinkedRun(linkedRun(productionRun));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onFinished(ProductionRun productionRun) {
        if (productionRun.getWorkflowRevisionId() == null) {
            return;
        }
        ObjectNode output = objectMapper.createObjectNode();
        output.put("actualOutputQty", productionRun.getActualOutputQty());
        flowRunCommand.finishLinkedRun(linkedRun(productionRun), output, productionRun.getFinishedBy());
    }

    private LinkedRun linkedRun(ProductionRun productionRun) {
        ObjectNode input = objectMapper.createObjectNode();
        input.put("plannedOutputQty", productionRun.getPlannedOutputQty());
        if (productionRun.getTargetItemId() != null) {
            input.put("targetItemId", productionRun.getTargetItemId());
        }
        if (productionRun.getWorkOrderId() != null) {
            input.put("workOrderId", productionRun.getWorkOrderId());
        }
        return new LinkedRun(productionRun.getProjectId(), productionRun.getWorkflowId(), productionRun.getWorkflowRevisionId(),
            productionRun.getProductionRunId(), productionRun.getRunType(), productionRun.getStartedBy(), input);
    }
}
