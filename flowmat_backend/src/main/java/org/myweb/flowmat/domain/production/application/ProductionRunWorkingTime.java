package org.myweb.flowmat.domain.production.application;

import java.time.OffsetDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWorkingTimeQuery;
import org.myweb.flowmat.domain.flowrun.application.publicapi.LinkedRunWorkingTime;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The work order equipment's available time for a production run's delayed steps
 * (docs/architecture/adr/ADR-015-shift-aware-delay.md decisions 1, 2 and 4). A run without a work order, or a work order
 * without equipment, keeps the time the delay ends.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductionRunWorkingTime implements LinkedRunWorkingTime {

    private final ProductionRunRepository productionRunRepository;
    private final WorkOrderRepository workOrderRepository;
    private final EquipmentWorkingTimeQuery equipmentWorkingTime;

    @Override
    public Optional<WorkingStart> workingStart(String productionRunId, OffsetDateTime readyAt) {
        return productionRunRepository.findById(productionRunId)
            .map(ProductionRun::getWorkOrderId)
            .flatMap(workOrderRepository::findById)
            .map(WorkOrder::getEquipmentId)
            .flatMap(equipmentId -> equipmentWorkingTime.nextWorkingTime(equipmentId, readyAt)
                .filter(at -> at.isAfter(readyAt))
                .map(at -> new WorkingStart(at, equipmentId)));
    }
}
