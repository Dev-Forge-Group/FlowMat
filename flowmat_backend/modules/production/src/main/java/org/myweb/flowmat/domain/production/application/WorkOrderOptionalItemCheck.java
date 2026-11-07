package org.myweb.flowmat.domain.production.application;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomStructureQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Checks the optional materials a work order picks (ADR-033 decision 5): each must be an optional material of the order's
 * BOM, or of a phantom it goes through on the order's planned day. Without a BOM yet, they must be items of the project.
 */
@Component
@RequiredArgsConstructor
class WorkOrderOptionalItemCheck {

    private final BomStructureQuery bomStructure;
    private final ProjectCalendarQuery calendar;
    private final ProductionPlanningReferences references;

    /** The requested ids trimmed, without blanks and repeats; null stays null ("leave them as they are"). */
    static List<String> normalize(List<String> requested) {
        if (requested == null) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (String id : requested) {
            String trimmed = ProductionText.trimToNull(id, "optionalItemIds");
            if (trimmed != null) {
                ids.add(trimmed);
            }
        }
        return List.copyOf(ids);
    }

    void check(WorkOrder order, List<String> picks) {
        if (picks.isEmpty()) {
            return;
        }
        if (order.getBomId() == null) {
            List<String> unknown = picks.stream()
                .filter(id -> references.item(id).filter(item -> order.getProjectId().equals(item.projectId())).isEmpty())
                .toList();
            if (!unknown.isEmpty()) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "An optional material to take was not found in this project.");
            }
            return;
        }
        LocalDate day = order.getPlannedStartAt() == null ? calendar.today(order.getProjectId())
            : calendar.date(order.getProjectId(), order.getPlannedStartAt().toInstant());
        Set<String> optional = bomStructure.optionalItemIds(order.getBomId(), day);
        List<String> wrong = picks.stream().filter(id -> !optional.contains(id)).toList();
        if (!wrong.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Not an optional material of the work order's BOM: "
                + String.join(", ", wrong.stream().map(this::code).toList()) + ".");
        }
    }

    /** At approval the BOM is settled, so the picks need it and must be its optional materials. */
    void checkForApproval(WorkOrder order, List<String> picks) {
        if (!picks.isEmpty() && order.getBomId() == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Work order " + order.getWorkOrderNumber() + " takes optional materials, so it needs a BOM.");
        }
        check(order, picks);
    }

    private String code(String itemId) {
        return references.item(itemId).map(CatalogItemView::itemCode).orElse(itemId);
    }
}
