package org.myweb.flowmat.domain.production.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunStep;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunSteps;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStepCompleteRequest;
import org.myweb.flowmat.domain.production.api.dto.request.ProductionRunStepFailRequest;
import org.myweb.flowmat.domain.production.api.dto.response.ProductionRunStepResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRunItem;
import org.myweb.flowmat.domain.production.repository.ProductionRunItemRepository;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A production run's process steps (docs/architecture/adr/ADR-011-production-steps.md), moved through the flow run public
 * API. Completing one takes what was recorded for the process as its output and, when the work instruction blocks finishing,
 * needs the process's required instruction steps confirmed first. Steps never move stock: run items still do (decision 4).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunStepService {
    private static final Set<String> OPEN = Set.of("pending", "running");

    private final ProductionRunRepository runRepository;
    private final ProductionRunItemRepository itemRepository;
    private final ProjectAccessService projectAccessService;
    private final RunInstructionService instructions;
    private final LinkedRunSteps linkedSteps;
    private final CatalogQuery catalogQuery;
    private final ObjectMapper objectMapper;

    public List<ProductionRunStepResponse> list(String productionRunId) {
        ProductionRun run = runRepository.findByProductionRunIdAndDeletedYn(productionRunId, "N")
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(run.getProjectId());
        Map<String, RunInstructionService.NodeChecklist> nodes = instructions.byNode(run);
        return linkedSteps.steps(productionRunId).stream().map(step -> response(step, nodes, run.getProjectId())).toList();
    }

    @Transactional
    public ProductionRunStepResponse start(String productionRunId, String stepId) {
        ProductionRun run = openRun(productionRunId);
        return response(linkedSteps.start(productionRunId, stepId), instructions.byNode(run), run.getProjectId());
    }

    @Transactional
    public ProductionRunStepResponse complete(String productionRunId, String stepId, ProductionRunStepCompleteRequest request) {
        ProductionRun run = openRun(productionRunId);
        LinkedRunStep step = linkedSteps.steps(productionRunId).stream()
            .filter(one -> one.stepId().equals(stepId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        // Decision 7: an instruction that blocks finishing also holds the process's step until its required steps are done.
        RunInstructionService.NodeChecklist node = instructions.byNode(run).get(step.nodeId());
        if (node != null && node.blocksFinish() && !node.missing().isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Confirm the required work instruction "
                + (node.missing().size() == 1 ? "step " : "steps ")
                + node.missing().stream().map(String::valueOf).collect(Collectors.joining(", "))
                + " for " + (step.nodeName() != null ? step.nodeName() : step.nodeId()) + " first.");
        }
        JsonNode output = request != null && request.outputSnapshot() != null
            ? request.outputSnapshot() : recordedOutput(run, step.nodeId());
        return response(linkedSteps.complete(productionRunId, stepId, output), instructions.byNode(run), run.getProjectId());
    }

    @Transactional
    public ProductionRunStepResponse fail(String productionRunId, String stepId, ProductionRunStepFailRequest request) {
        ProductionRun run = openRun(productionRunId);
        ProductionText.requireStorable(request.errorCode(), "errorCode");
        ProductionText.requireStorable(request.errorMessage(), "errorMessage");
        return response(linkedSteps.fail(productionRunId, stepId, request.errorCode().trim(), request.errorMessage()),
            instructions.byNode(run), run.getProjectId());
    }

    private ProductionRun openRun(String productionRunId) {
        ProductionRun run = runRepository.findForUpdate(productionRunId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectWriteAccess(run.getProjectId());
        if (!OPEN.contains(run.getRunStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Run " + run.getRunNumber() + " is finished; its process steps can no longer change.");
        }
        return run;
    }

    /**
     * What was recorded for the process (decision 3): the quantity, unit and item of its outputs that were not cancelled,
     * when they are one item in one unit; otherwise nothing.
     */
    private JsonNode recordedOutput(ProductionRun run, String nodeId) {
        List<ProductionRunItem> outputs = itemRepository.findAllByProductionRunIdOrderByProductionRunItemIdAsc(run.getProductionRunId())
            .stream()
            .filter(item -> "output".equals(item.getDirection()) && nodeId.equals(item.getProcessId()) && !item.isCancelled())
            .toList();
        ObjectNode output = objectMapper.createObjectNode();
        if (outputs.isEmpty()) {
            return output;
        }
        String itemId = outputs.get(0).getItemId();
        String unit = outputs.get(0).getUnit();
        if (outputs.stream().anyMatch(item -> !itemId.equals(item.getItemId()) || !unit.equals(item.getUnit()))) {
            return output;
        }
        BigDecimal quantity = outputs.stream()
            .map(item -> item.getActualQty() != null ? item.getActualQty() : item.getPlannedQty())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return output.put("quantity", quantity).put("unit", unit).put("item", itemId);
    }

    private ProductionRunStepResponse response(LinkedRunStep step, Map<String, RunInstructionService.NodeChecklist> nodes,
                                               String projectId) {
        RunInstructionService.NodeChecklist node = nodes.get(step.nodeId());
        return new ProductionRunStepResponse(step.stepId(), step.nodeId(), step.nodeName(), step.sequenceNo(), step.status(),
            step.gateway(), step.sourceStepId(), step.sourceConnectionId(), step.scheduledAt(), step.startedAt(), step.endedAt(),
            step.errorCode(), step.errorMessage(), step.inputSnapshot(), step.outputSnapshot(), step.joinWaiting(), step.timeoutAt(),
            step.overdueAt(),
            node == null ? 0 : node.required(), node == null ? 0 : node.done(), held(step.held(), projectId));
    }

    /** What the step waits for past its delay, the equipment by its code (ADR-015, ADR-016); null when it was not held. */
    private ProductionRunStepResponse.Held held(LinkedRunStep.Held held, String projectId) {
        if (held == null) {
            return null;
        }
        String code = held.equipmentId() == null ? null : catalogQuery.findProjectEquipment(projectId, held.equipmentId())
            .map(CatalogEquipmentView::equipmentCode)
            .orElse(null);
        return new ProductionRunStepResponse.Held(held.readyAt(), held.equipmentId(), code, held.crew());
    }
}
