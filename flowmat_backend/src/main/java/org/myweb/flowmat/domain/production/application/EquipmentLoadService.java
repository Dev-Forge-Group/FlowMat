package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.EquipmentScheduleService;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentCostQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWindow;
import org.myweb.flowmat.domain.production.api.dto.response.EquipmentLoadResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * The equipment load board (docs/domain/equipment-load.md): per equipment, the hours its calendar leaves in a window
 * against what its draft, approved and running orders need there. An order that runs past the window counts in proportion
 * to its equipment's available time inside the window. Read only, like readiness, so it has no transaction of its own.
 * Equipment, items, calendars and changeovers are read through {@link CatalogQuery} (ADR-002).
 */
@Service
@RequiredArgsConstructor
public class EquipmentLoadService {

    /** The longest window the board covers. */
    static final Duration LONGEST_WINDOW = Duration.ofDays(92);
    private static final String NOT_DELETED = "N";
    private static final String FINISHED_RUN = "finished";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final WorkOrderRepository workOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final CatalogQuery catalogQuery;
    private final EquipmentCostQuery equipmentCosts;
    private final ProjectAccessService projectAccessService;

    public EquipmentLoadResponse load(String projectId, String from, String to) {
        if (projectId == null || projectId.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId);
        OffsetDateTime start = dateTime(from, "from");
        OffsetDateTime end = dateTime(to, "to");
        if (!end.isAfter(start)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "to must be after from.");
        }
        if (Duration.between(start, end).compareTo(LONGEST_WINDOW) > 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The load board covers at most 92 days.");
        }

        List<WorkOrder> orders = workOrderRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED).stream()
            .filter(order -> order.getEquipmentId() != null)
            .toList();
        Map<String, BigDecimal> produced = produced(orders);
        Map<String, String> itemCodes = new HashMap<>();
        catalogQuery.findItems(orders.stream()
                .map(WorkOrder::getTargetItemId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()))
            .values()
            .forEach(item -> itemCodes.put(item.itemId(), item.itemCode()));
        Map<String, List<WorkOrder>> byEquipment = orders.stream().collect(Collectors.groupingBy(WorkOrder::getEquipmentId));

        Map<String, BigDecimal> hourlyCosts = equipmentCosts.findHourlyCosts(projectId, byEquipment.keySet());

        List<EquipmentLoadResponse.Row> rows = catalogQuery.findProjectEquipments(projectId).stream()
            .sorted(Comparator.comparing(CatalogEquipmentView::label, String.CASE_INSENSITIVE_ORDER))
            .map(equipment -> row(equipment, byEquipment.getOrDefault(equipment.equipmentId(), List.of()), start, end, produced, itemCodes, hourlyCosts.get(equipment.equipmentId())))
            .toList();
        return new EquipmentLoadResponse(start, end, rows);
    }

    private EquipmentLoadResponse.Row row(
        CatalogEquipmentView equipment,
        List<WorkOrder> mine,
        OffsetDateTime from,
        OffsetDateTime to,
        Map<String, BigDecimal> produced,
        Map<String, String> itemCodes,
        BigDecimal hourlyCost
    ) {
        EquipmentWindow window = window(equipment.equipmentId(), from, to);
        // At the output it really reaches (docs/domain/equipment.md E9); changeover minutes are not scaled.
        BigDecimal rate = equipment.capacityPerHour() != null && equipment.capacityPerHour().signum() > 0
            ? equipment.effectiveCapacityPerHour()
            : null;
        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal drafts = BigDecimal.ZERO;
        int unplanned = 0;
        int unmeasured = 0;
        List<EquipmentLoadResponse.Order> shown = new ArrayList<>();
        List<WorkOrder> inWindowOrders = new ArrayList<>();
        List<WorkOrder> sorted = mine.stream()
            .sorted(Comparator.comparing(WorkOrder::getPlannedStartAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        for (WorkOrder order : sorted) {
            WorkOrderStatus status = WorkOrderServiceImpl.status(order);
            if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED) {
                continue;
            }
            OffsetDateTime start = order.getPlannedStartAt();
            OffsetDateTime end = order.getPlannedEndAt();
            if (start == null || end == null || !end.isAfter(start)) {
                unplanned++;
                continue;
            }
            if (!start.isBefore(to) || !end.isAfter(from)) {
                continue;
            }
            BigDecimal remaining = order.getTargetQuantity() == null
                ? null
                : order.getTargetQuantity().subtract(produced.getOrDefault(order.getWorkOrderId(), BigDecimal.ZERO))
                    .max(BigDecimal.ZERO);
            Integer changeover = changeoverMinutes(equipment.equipmentId(), order, mine);
            BigDecimal needed = neededHours(remaining, rate, changeover);
            BigDecimal inWindow = needed == null ? null : inWindow(equipment.equipmentId(), needed, start, end, from, to);
            if (inWindow == null) {
                unmeasured++;
            } else if (status == WorkOrderStatus.DRAFT) {
                drafts = drafts.add(inWindow);
            } else {
                planned = planned.add(inWindow);
            }
            inWindowOrders.add(order);
            shown.add(new EquipmentLoadResponse.Order(order.getWorkOrderId(), order.getWorkOrderNumber(), order.getWorkOrderTitle(),
                order.getWorkOrderStatus(), order.getTargetItemId(), itemCodes.get(order.getTargetItemId()), start, end, remaining,
                changeover, needed, inWindow, setupCost(changeover, hourlyCost)));
        }
        BigDecimal available = window.availableHours();
        // Up to N side by side it has N times the hours (E18); with no limit it is never overloaded (E14).
        Integer limit = equipment.maxConcurrent();
        BigDecimal capacity = limit == null ? available : available.multiply(BigDecimal.valueOf(limit));
        BigDecimal percent = capacity.signum() > 0 ? planned.multiply(HUNDRED).divide(capacity, 1, RoundingMode.HALF_UP) : null;
        return new EquipmentLoadResponse.Row(equipment.equipmentId(), equipment.equipmentCode(), equipment.equipmentName(),
            equipment.equipmentStatus(), equipment.capacityPerHour(), equipment.efficiencyPercent(), equipment.concurrentWorkOrders(),
            equipment.concurrentLimit(), window.calendarSet(), available, window.downtimeHours(),
            planned, drafts, percent, limit != null && planned.compareTo(capacity) > 0, unplanned, unmeasured, shown,
            changeovers(equipment.equipmentId(), inWindowOrders, mine));
    }

    /** The equipment's working time in [from, to); none when it is gone since the list was read. */
    private EquipmentWindow window(String equipmentId, OffsetDateTime from, OffsetDateTime to) {
        return catalogQuery.equipmentWindow(equipmentId, from, to)
            .orElse(new EquipmentWindow(false, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    /** Estimate for the whole displayed order, using exact minutes and the current planning rate. */
    private static BigDecimal setupCost(Integer minutes, BigDecimal rate) {
        if (minutes == null) return null;
        if (minutes == 0) return BigDecimal.ZERO.setScale(4);
        return rate == null ? null : rate.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP);
    }

    /** Production time for what is left plus the changeover; null without a quantity or a capacity per hour. */
    private static BigDecimal neededHours(BigDecimal remaining, BigDecimal rate, Integer changeoverMinutes) {
        if (remaining == null || rate == null) {
            return null;
        }
        BigDecimal production = remaining.signum() > 0 ? remaining.divide(rate, 2, RoundingMode.UP) : BigDecimal.ZERO;
        BigDecimal changeover = changeoverMinutes == null
            ? BigDecimal.ZERO
            : BigDecimal.valueOf(changeoverMinutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.UP);
        return production.add(changeover);
    }

    /**
     * The part of {@code needed} that falls in [from, to): all of it when the order lies inside, otherwise in proportion to
     * the equipment's available time (or, when the order's own window has none, to clock time).
     */
    private BigDecimal inWindow(String equipmentId, BigDecimal needed, OffsetDateTime start, OffsetDateTime end,
                                OffsetDateTime from, OffsetDateTime to) {
        if (!start.isBefore(from) && !end.isAfter(to)) {
            return needed;
        }
        OffsetDateTime partStart = start.isBefore(from) ? from : start;
        OffsetDateTime partEnd = end.isAfter(to) ? to : end;
        if (Duration.between(start, end).compareTo(EquipmentScheduleService.LONGEST_WINDOW) <= 0) {
            BigDecimal whole = window(equipmentId, start, end).availableHours();
            if (whole.signum() > 0) {
                BigDecimal part = window(equipmentId, partStart, partEnd).availableHours();
                return needed.multiply(part).divide(whole, 2, RoundingMode.HALF_UP);
            }
        }
        BigDecimal wholeSeconds = seconds(Duration.between(start, end));
        BigDecimal partSeconds = seconds(Duration.between(partStart, partEnd));
        return needed.multiply(partSeconds).divide(wholeSeconds, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal seconds(Duration duration) {
        return BigDecimal.valueOf(duration.getSeconds()).add(BigDecimal.valueOf(duration.getNano(), 9));
    }

    private Integer changeoverMinutes(String equipmentId, WorkOrder order, List<WorkOrder> mine) {
        if (order.getTargetItemId() == null) {
            return null;
        }
        List<WorkOrder> others = mine.stream().filter(other -> !other.getWorkOrderId().equals(order.getWorkOrderId())).toList();
        WorkOrder previous = EquipmentSequence.previous(order.getPlannedStartAt(), others);
        if (previous == null) {
            return null;
        }
        OptionalInt minutes = catalogQuery.changeoverMinutes(equipmentId, previous.getTargetItemId(), order.getTargetItemId());
        return minutes.isPresent() ? minutes.getAsInt() : null;
    }

    /**
     * The window's changeovers in planned order and, when another order of the same work orders changes over less, that
     * order (docs/domain/equipment-load.md "전환 순서 제안"). Only orders with a target item take part, drafts included;
     * running orders stay first. The item before is the one the equipment ran last before the first of them. Null with
     * fewer than two such orders or no changeover time in the planned order.
     */
    private EquipmentLoadResponse.ChangeoverPlan changeovers(String equipmentId, List<WorkOrder> inWindow, List<WorkOrder> mine) {
        List<WorkOrder> sequence = inWindow.stream().filter(order -> order.getTargetItemId() != null).toList();
        if (sequence.size() < 2) {
            return null;
        }
        Set<String> taking = sequence.stream().map(WorkOrder::getWorkOrderId).collect(Collectors.toSet());
        WorkOrder previous = EquipmentSequence.previous(sequence.get(0).getPlannedStartAt(),
            mine.stream().filter(order -> !taking.contains(order.getWorkOrderId())).toList());
        Set<Integer> running = new HashSet<>();
        for (int index = 0; index < sequence.size(); index++) {
            if (WorkOrderServiceImpl.status(sequence.get(index)) == WorkOrderStatus.IN_PROGRESS) {
                running.add(index);
            }
        }
        Map<String, Integer> known = new HashMap<>();
        ToIntBiFunction<String, String> minutes = (fromItem, toItem) -> known.computeIfAbsent(fromItem + "\n" + toItem, key -> {
            OptionalInt found = catalogQuery.changeoverMinutes(equipmentId, fromItem, toItem);
            return found.isPresent() ? found.getAsInt() : 0;
        });
        ChangeoverOrder.Plan plan = ChangeoverOrder.plan(sequence.stream().map(WorkOrder::getTargetItemId).toList(), running,
            previous == null ? null : previous.getTargetItemId(), minutes);
        if (plan.plannedMinutes() == 0) {
            return null;
        }
        return new EquipmentLoadResponse.ChangeoverPlan(plan.plannedMinutes(), plan.suggestedMinutes(),
            plan.suggestedOrder() == null ? null
                : plan.suggestedOrder().stream().map(index -> sequence.get(index).getWorkOrderNumber()).toList());
    }

    /** What finished runs of each order produced, the same measure as the work order list. */
    private Map<String, BigDecimal> produced(Collection<WorkOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> produced = new HashMap<>();
        for (ProductionRun run : productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(
            orders.stream().map(WorkOrder::getWorkOrderId).toList(), NOT_DELETED)) {
            if (run.affectsPhysicalState() && FINISHED_RUN.equalsIgnoreCase(run.getRunStatus())
                && run.getActualOutputQty() != null) {
                produced.merge(run.getWorkOrderId(), run.getActualOutputQty(), BigDecimal::add);
            }
        }
        return produced;
    }

    private static OffsetDateTime dateTime(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                name + " must be a date and time with an offset, such as 2030-01-07T00:00:00+09:00.");
        }
    }
}
