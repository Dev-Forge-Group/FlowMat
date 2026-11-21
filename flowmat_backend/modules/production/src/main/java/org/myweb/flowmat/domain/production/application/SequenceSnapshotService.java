package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Job;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.LeftOut;
import org.myweb.flowmat.domain.production.api.dto.response.SequenceSnapshotResponse.Slot;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * The input for comparing orders of one equipment's work orders in a week
 * (docs/architecture/adr/ADR-039-work-order-sequence-optimizer.md decision 2). It takes the same work orders as the load
 * board's changeover suggestion (docs/domain/equipment-load.md Q1): open ones with a target item and a plan that reaches
 * into the window, drafts included, running ones first. Read only.
 */
@Service
@RequiredArgsConstructor
public class SequenceSnapshotService {

    /** The window the comparison covers. */
    static final Duration WINDOW = Duration.ofDays(7);
    /** How far past the window's start jobs may run on: work that spills out of the week still counts. */
    static final Duration HORIZON = Duration.ofDays(28);
    private static final String NOT_DELETED = "N";

    private final WorkOrderRepository workOrderRepository;
    private final EquipmentLoadService loadService;
    private final CatalogQuery catalogQuery;
    private final ProjectAccess access;

    public SequenceSnapshotResponse snapshot(String projectId, String equipmentId, String from) {
        if (projectId == null || projectId.isBlank()) throw bad("projectId is required.");
        access.requireProjectReadAccess(projectId);
        CatalogEquipmentView equipment = equipmentId == null ? null
            : catalogQuery.findProjectEquipment(projectId, equipmentId).orElse(null);
        if (equipment == null) throw new BusinessException(ErrorCode.NOT_FOUND, "No such equipment in this project.");
        OffsetDateTime start = dateTime(from);
        OffsetDateTime end = start.plus(WINDOW);
        OffsetDateTime horizonEnd = start.plus(HORIZON);
        BigDecimal rate = equipment.capacityPerHour() != null && equipment.capacityPerHour().signum() > 0
            ? equipment.effectiveCapacityPerHour() : null;

        List<WorkOrder> mine = workOrderRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED).stream()
            .filter(order -> equipment.equipmentId().equals(order.getEquipmentId()))
            .toList();
        Map<String, BigDecimal> produced = loadService.produced(mine);
        List<WorkOrder> taking = new ArrayList<>();
        List<LeftOut> leftOut = new ArrayList<>();
        mine.stream().sorted(Comparator.comparing(WorkOrder::getPlannedStartAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(WorkOrder::getWorkOrderNumber, Comparator.nullsLast(Comparator.naturalOrder())))
            .forEach(order -> {
                WorkOrderStatus status = WorkOrderServiceImpl.status(order);
                if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED) return;
                if (order.getPlannedStartAt() == null || order.getPlannedEndAt() == null
                    || !order.getPlannedEndAt().isAfter(order.getPlannedStartAt())) return;
                if (!order.getPlannedStartAt().isBefore(end) || !order.getPlannedEndAt().isAfter(start)) return;
                if (order.getTargetItemId() == null) {
                    leftOut.add(new LeftOut(order.getWorkOrderNumber(), "no target item"));
                } else if (order.getTargetQuantity() == null) {
                    leftOut.add(new LeftOut(order.getWorkOrderNumber(), "no target quantity"));
                } else if (rate == null) {
                    leftOut.add(new LeftOut(order.getWorkOrderNumber(), "the equipment has no capacity per hour"));
                } else {
                    taking.add(order);
                }
            });

        // Running orders first, each part in planned order, as the load board's suggestion keeps them.
        List<WorkOrder> ordered = new ArrayList<>();
        taking.stream().filter(this::running).forEach(ordered::add);
        taking.stream().filter(order -> !running(order)).forEach(ordered::add);
        List<Job> jobs = new ArrayList<>();
        for (WorkOrder order : ordered) {
            BigDecimal remaining = order.getTargetQuantity().subtract(produced.getOrDefault(order.getWorkOrderId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
            long seconds = remaining.multiply(BigDecimal.valueOf(3600)).divide(rate, 0, RoundingMode.UP).longValueExact();
            jobs.add(new Job(order.getWorkOrderId(), order.getWorkOrderNumber(), order.getTargetItemId(),
                WorkOrderServiceImpl.status(order).code(), running(order), remaining, seconds, order.getPlannedStartAt(),
                order.getPlannedEndAt(), order.getPriority() == null ? "normal" : order.getPriority()));
        }

        Set<String> takenIds = taking.stream().map(WorkOrder::getWorkOrderId).collect(Collectors.toSet());
        WorkOrder before = taking.isEmpty() ? null : EquipmentSequence.previous(taking.stream().map(WorkOrder::getPlannedStartAt)
            .min(Comparator.naturalOrder()).orElseThrow(), mine.stream().filter(order -> !takenIds.contains(order.getWorkOrderId())).toList());
        String itemBefore = before == null ? null : before.getTargetItemId();
        Set<String> items = new LinkedHashSet<>();
        if (itemBefore != null) items.add(itemBefore);
        jobs.forEach(job -> items.add(job.item()));
        Map<String, Map<String, Integer>> changeovers = new LinkedHashMap<>();
        for (String fromItem : items) {
            Map<String, Integer> next = new LinkedHashMap<>();
            for (String toItem : items) {
                OptionalInt found = catalogQuery.changeoverMinutes(equipment.equipmentId(), fromItem, toItem);
                next.put(toItem, found.isPresent() ? found.getAsInt() : 0);
            }
            changeovers.put(fromItem, next);
        }
        List<Slot> available = catalogQuery.availableSlots(equipment.equipmentId(), start, horizonEnd).orElse(List.of()).stream()
            .map(slot -> new Slot(slot.start(), slot.end())).toList();

        SequenceSnapshotResponse input = new SequenceSnapshotResponse(SequenceSnapshotResponse.SCHEMA_VERSION, projectId,
            equipment.equipmentId(), start, end, horizonEnd, itemBefore, available, List.copyOf(jobs), changeovers,
            SequenceCost.WEIGHTS, List.copyOf(leftOut), null, null);
        return withBaselines(input);
    }

    /**
     * The snapshot with what the plan and the current rule cost. The plan runs the jobs as listed; the current rule is the
     * load board's changeover suggestion (docs/domain/equipment-load.md Q3), or the plan when it has none.
     */
    public static SequenceSnapshotResponse withBaselines(SequenceSnapshotResponse snapshot) {
        List<Job> jobs = snapshot.jobs();
        List<String> planned = jobs.stream().map(Job::workOrderNumber).toList();
        Set<Integer> fixed = new HashSet<>();
        for (int index = 0; index < jobs.size(); index++) {
            if (jobs.get(index).fixed()) fixed.add(index);
        }
        ChangeoverOrder.Plan rule = ChangeoverOrder.plan(jobs.stream().map(Job::item).toList(), fixed, snapshot.itemBefore(),
            (fromItem, toItem) -> SequenceCost.minutes(snapshot, fromItem, toItem));
        List<String> current = rule.suggestedOrder() == null ? planned
            : rule.suggestedOrder().stream().map(index -> jobs.get(index).workOrderNumber()).toList();
        return new SequenceSnapshotResponse(snapshot.schemaVersion(), snapshot.projectId(), snapshot.equipmentId(), snapshot.from(),
            snapshot.to(), snapshot.horizonEnd(), snapshot.itemBefore(), snapshot.available(), snapshot.jobs(), snapshot.changeovers(),
            snapshot.weights(), snapshot.leftOut(), SequenceCost.of(snapshot, planned), SequenceCost.of(snapshot, current));
    }

    private boolean running(WorkOrder order) {
        return WorkOrderServiceImpl.status(order) == WorkOrderStatus.IN_PROGRESS;
    }

    private static OffsetDateTime dateTime(String value) {
        if (value == null || value.isBlank()) throw bad("from is required.");
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad("from must be a date and time with an offset, such as 2030-01-07T00:00:00+09:00.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
