package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRequirement;
import org.myweb.flowmat.domain.bom.application.publicapi.BomQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogDisposalCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderWasteDisposalResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A work order's waste disposal cost estimate (docs/domain/bom-by-products.md WD8): the waste lines of its BOM for the
 * quantity still to make, as its run will freeze them (the planned day's revision for phantoms), times today's disposal
 * costs. The same pricing rules as a run's (WD5): zero is free, null is unknown and left out of the known subtotal.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class WorkOrderWasteDisposalService {
    private static final int SCALE = 4;
    private static final String NOT_DELETED = "N";

    private final WorkOrderRepository workOrders;
    private final ProductionRunRepository runs;
    private final BomQuery bomQuery;
    private final CatalogQuery catalog;
    private final CatalogDisposalCostQuery disposalCosts;
    private final ProjectCalendarQuery projectCalendar;
    private final ProjectAccessService access;
    private final WorkOrderOptionalItems optionalItems;

    public WorkOrderWasteDisposalResponse estimate(String workOrderId) {
        WorkOrder order = workOrders.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String projectId = order.getProjectId();
        access.requireProjectReadAccess(projectId);
        if (order.getBomId() == null || order.getTargetQuantity() == null) {
            return empty(order, null, "The work order has no BOM and quantity to estimate from.");
        }
        BigDecimal produced = runs.findAllByWorkOrderIdInAndDeletedYn(List.of(workOrderId), NOT_DELETED).stream()
            .filter(run -> run.affectsPhysicalState() && "finished".equalsIgnoreCase(run.getRunStatus()))
            .map(ProductionRun::getActualOutputQty)
            .filter(Objects::nonNull)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = order.getTargetQuantity().subtract(produced);
        if (remaining.signum() <= 0) {
            return empty(order, BigDecimal.ZERO.setScale(SCALE), null);
        }
        LocalDate phantomDay = order.getPlannedStartAt() == null ? null
            : projectCalendar.date(projectId, order.getPlannedStartAt().toInstant());
        BomRequirement requirement;
        try {
            requirement = bomQuery.requirementsForRun(order.getBomId(), projectId, order.getTargetItemId(), remaining, phantomDay,
                optionalItems.of(order.getWorkOrderId()));
        } catch (BusinessException exception) {
            return empty(order, remaining, "The BOM could not be used: " + exception.getMessage());
        }
        Map<String, BigDecimal> quantities = new LinkedHashMap<>();
        Map<String, Boolean> convertible = new LinkedHashMap<>();
        for (BomRequirement.Output output : requirement.outputs() == null ? List.<BomRequirement.Output>of()
            : requirement.outputs()) {
            if (!"waste".equals(output.lineType())) continue;
            convertible.putIfAbsent(output.itemId(), true);
            if (output.itemQuantity() == null) {
                convertible.put(output.itemId(), false);
            } else {
                quantities.merge(output.itemId(), output.itemQuantity(), BigDecimal::add);
            }
        }
        var items = catalog.findItems(convertible.keySet());
        var prices = disposalCosts.findDisposalCostsAt(projectId, convertible.keySet(), null);
        List<WorkOrderWasteDisposalResponse.Line> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (var entry : convertible.entrySet()) {
            String id = entry.getKey();
            var item = items.get(id);
            if (item != null && !projectId.equals(item.projectId())) item = null;
            var price = prices.get(id);
            BigDecimal quantity = entry.getValue() ? quantities.getOrDefault(id, BigDecimal.ZERO).setScale(SCALE, RoundingMode.HALF_UP) : null;
            // Zero is a known cost here (free disposal); only null is unknown.
            BigDecimal unitCost = price == null ? null : price.unitCost();
            BigDecimal cost = quantity != null && unitCost != null ? quantity.multiply(unitCost).setScale(SCALE, RoundingMode.HALF_UP) : null;
            if (cost == null) complete = false;
            else total = total.add(cost);
            lines.add(new WorkOrderWasteDisposalResponse.Line(id, item == null ? id : item.itemCode(),
                item == null ? null : item.itemName(), quantity, item == null ? null : item.unitCode(), unitCost, cost));
        }
        lines.sort(Comparator.comparing(WorkOrderWasteDisposalResponse.Line::itemCode));
        return new WorkOrderWasteDisposalResponse(workOrderId, order.getBomId(), remaining.setScale(SCALE, RoundingMode.HALF_UP),
            total.setScale(SCALE, RoundingMode.HALF_UP), complete, lines, null);
    }

    private static WorkOrderWasteDisposalResponse empty(WorkOrder order, BigDecimal quantity, String problem) {
        return new WorkOrderWasteDisposalResponse(order.getWorkOrderId(), order.getBomId(), quantity,
            BigDecimal.ZERO.setScale(SCALE), problem == null, List.of(), problem);
    }
}
