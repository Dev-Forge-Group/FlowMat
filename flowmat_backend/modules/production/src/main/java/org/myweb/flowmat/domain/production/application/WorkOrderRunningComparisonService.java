package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.myweb.flowmat.domain.production.api.dto.response.RunEquipmentTimeResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderRunningComparisonResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A work order's planned running time and its runs' recorded running time (docs/domain/equipment-setup-cost.md ER8). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderRunningComparisonService {
    private static final String NOT_DELETED = "N";
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    private final WorkOrderRepository workOrders;
    private final ProductionRunRepository runs;
    private final CatalogQuery catalogQuery;
    private final EquipmentCostQuery equipmentCosts;
    private final RunEquipmentTimeService runTimes;
    private final ProjectAccessService access;

    public WorkOrderRunningComparisonResponse compare(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String projectId = order.getProjectId();
        access.requireProjectReadAccess(projectId);

        // Planned: the whole target at the output the equipment really reaches (capacity times efficiency, equipment.md E9),
        // as the plan suggestion reckons hours, at today's rate.
        String equipmentId = order.getEquipmentId();
        CatalogEquipmentView equipment = equipmentId == null ? null
            : catalogQuery.findProjectEquipment(projectId, equipmentId).orElse(null);
        BigDecimal capacity = equipment == null ? null : equipment.capacityPerHour();
        BigDecimal effective = equipment == null ? null : equipment.effectiveCapacityPerHour();
        Integer plannedMinutes = null;
        BigDecimal plannedCost = null;
        BigDecimal target = order.getTargetQuantity();
        if (capacity != null && capacity.signum() > 0 && target != null && target.signum() > 0) {
            plannedMinutes = target.multiply(MINUTES_PER_HOUR).divide(effective, 0, RoundingMode.CEILING).intValueExact();
            BigDecimal rate = equipmentCosts.findHourlyRate(projectId, equipmentId).map(EquipmentCostQuery.EquipmentRate::hourlyCost)
                .orElse(null);
            plannedCost = rate == null ? null
                : rate.multiply(BigDecimal.valueOf(plannedMinutes)).divide(MINUTES_PER_HOUR, 4, RoundingMode.HALF_UP);
        }

        // Actual: the running time its physical runs recorded, at the rates it was recorded at (ER2).
        int count = 0;
        int minutes = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean complete = true;
        for (ProductionRun run : runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED)) {
            if (!run.affectsPhysicalState()) continue;
            count++;
            RunEquipmentTimeResponse times = runTimes.times(run.getProductionRunId());
            minutes += times.runningMinutes();
            cost = cost.add(times.runningCost());
            if (!times.costComplete()) complete = false;
        }
        return new WorkOrderRunningComparisonResponse(workOrderId, equipmentId, capacity, plannedMinutes, plannedCost, count, minutes,
            cost.setScale(4, RoundingMode.HALF_UP), complete, equipment == null ? null : equipment.efficiencyPercent());
    }
}
