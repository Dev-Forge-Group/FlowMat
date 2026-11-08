package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalInt;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.myweb.flowmat.domain.production.api.dto.response.RunSetupCostResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderSetupComparisonResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A work order's planned setup estimate and its runs' actual setups (docs/domain/equipment-setup-cost.md AS11). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderSetupComparisonService {
    private static final String NOT_DELETED = "N";

    private final WorkOrderRepository workOrders;
    private final ProductionRunRepository runs;
    private final CatalogQuery catalogQuery;
    private final EquipmentCostQuery equipmentCosts;
    private final RunSetupService runSetups;
    private final ProjectAccessService access;

    public WorkOrderSetupComparisonResponse compare(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String projectId = order.getProjectId();
        access.requireProjectReadAccess(projectId);

        // Planned: the changeover from the order before it on the same equipment, as on the load board (SC6).
        Integer plannedMinutes = null;
        BigDecimal plannedCost = null;
        String equipmentId = order.getEquipmentId();
        if (equipmentId != null && order.getTargetItemId() != null && order.getPlannedStartAt() != null) {
            List<WorkOrder> sameEquipment = workOrders.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED).stream()
                .filter(other -> equipmentId.equals(other.getEquipmentId()) && !other.getWorkOrderId().equals(workOrderId))
                .toList();
            WorkOrder previous = EquipmentSequence.previous(order.getPlannedStartAt(), sameEquipment);
            if (previous != null) {
                OptionalInt minutes = catalogQuery.changeoverMinutes(equipmentId, previous.getTargetItemId(), order.getTargetItemId());
                plannedMinutes = minutes.isPresent() ? minutes.getAsInt() : null;
            }
            BigDecimal rate = equipmentCosts.findHourlyRate(projectId, equipmentId).map(EquipmentCostQuery.EquipmentRate::hourlyCost)
                .orElse(null);
            plannedCost = plannedMinutes == null ? null : plannedMinutes == 0 ? BigDecimal.ZERO.setScale(4)
                : rate == null ? null : rate.multiply(BigDecimal.valueOf(plannedMinutes)).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
        }

        // Actual: every setup its physical runs recorded, at the rates they were recorded at (AS2).
        int count = 0;
        int minutes = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean complete = true;
        for (ProductionRun run : runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED)) {
            if (!run.affectsPhysicalState()) continue;
            count++;
            RunSetupCostResponse setups = runSetups.setups(run.getProductionRunId());
            minutes += setups.setupMinutes();
            if (setups.setupCost() != null) cost = cost.add(setups.setupCost());
            if (!setups.costComplete()) complete = false;
        }
        return new WorkOrderSetupComparisonResponse(workOrderId, equipmentId, plannedMinutes, plannedCost, count, minutes,
            cost.setScale(4, RoundingMode.HALF_UP), complete);
    }
}
