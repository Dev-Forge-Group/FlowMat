package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.response.BomRequirementResponse;
import org.myweb.flowmat.domain.bom.application.BomService;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentWindow;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderReadinessResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderReadinessResponse.Check;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderReadinessResponse.Material;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.StockAllocationRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Read-only check of whether a work order can run now (docs/domain/work-order-readiness.md). It deliberately has no
 * surrounding transaction: a failing BOM calculation is reported as a failed check instead of rolling anything back.
 */
@Service
@RequiredArgsConstructor
public class WorkOrderReadinessService {

    static final String OK = "ok";
    static final String WARN = "warn";
    static final String FAIL = "fail";
    private static final String NOT_DELETED = "N";

    private final WorkOrderRepository workOrderRepository;
    private final org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProductionQuery workflows;
    private final ProductionPlanningReferences references;
    private final org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery projectCalendar;
    private final ProductionRunRepository productionRunRepository;
    private final BomService bomService;
    private final OpenRunInputs openRunInputs;
    private final CatalogQuery catalog;
    private final StockAllocationRepository stockAllocationRepository;

    /** LOTs expiring within this many days are called out (same setting as the stock alerts). */
    @Value("${app.stock-alert.expiry-warning-days:7}")
    private int expiryWarningDays;
    private final ProjectAccessService projectAccessService;
    private final WorkOrderCrewRepository crewRepository;
    private final WorkerCalendarQuery workerCalendars;

    public WorkOrderReadinessResponse check(String workOrderId) {
        WorkOrder order = workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(order.getProjectId());
        List<Check> checks = new ArrayList<>();

        WorkOrderStatus status = WorkOrderServiceImpl.status(order);
        if (status.acceptsRuns()) {
            checks.add(new Check("status", OK, "Work order is " + order.getWorkOrderStatus() + "."));
        } else if (status == WorkOrderStatus.DRAFT) {
            checks.add(new Check("status", FAIL, "Approve the work order before starting a run."));
        } else {
            checks.add(new Check("status", FAIL, "Work order is " + order.getWorkOrderStatus() + "; it takes no more runs."));
        }

        if (order.getWorkflowId() == null) {
            checks.add(new Check("workflow", FAIL, "Choose the workflow the order runs on."));
        } else {
            checks.add(workflows.latestPublishedRevision(order.getWorkflowId())
                .map(revision -> new Check("workflow", OK, "Runs start on workflow revision v" + revision.revisionNo() + "."))
                .orElseGet(() -> new Check("workflow", WARN,
                    "The workflow has no published revision; publish one so runs are fixed to a version.")));
        }

        BigDecimal remaining = remainingQuantity(order);
        if (order.getTargetItemId() == null) {
            checks.add(new Check("target", WARN, "No target item; what the runs produce is not checked."));
        }
        if (remaining == null) {
            checks.add(new Check("quantity", WARN, "No target quantity; materials are not checked."));
        } else if (remaining.signum() <= 0) {
            checks.add(new Check("quantity", WARN, "The target quantity has already been produced."));
        } else {
            checks.add(new Check("quantity", OK, "Still to produce: " + plain(remaining) + "."));
        }

        List<Material> materials = new ArrayList<>();
        BomHeader bom = order.getBomId() == null
            ? null
            : references.bom(order.getBomId()).orElse(null);
        if (order.getBomId() == null) {
            checks.add(new Check("bom", WARN, "No BOM; materials are neither planned nor checked."));
        } else if (bom == null) {
            checks.add(new Check("bom", FAIL, "The work order's BOM no longer exists."));
        } else if (!"approved".equals(bom.getBomStatus())) {
            checks.add(new Check("bom", FAIL,
                "BOM " + bom.getBomName() + " v" + bom.getBomVersion() + " is " + bom.getBomStatus() + "; approve it first."));
        } else {
            checks.add(new Check("bom", OK, "BOM " + bom.getBomName() + " v" + bom.getBomVersion() + " is approved."));
            if (remaining != null && remaining.signum() > 0) {
                materials = materials(order, bom, remaining, checks);
            }
        }

        if (order.getEquipmentId() != null) {
            equipmentChecks(order, remaining, checks);
        }
        crewChecks(order, checks);

        boolean ready = checks.stream().noneMatch(check -> FAIL.equals(check.status()));
        return new WorkOrderReadinessResponse(order.getWorkOrderId(), ready, remaining, checks, materials);
    }

    /**
     * Only for an order with equipment assigned (docs/domain/equipment-schedule.md): whether the equipment can work,
     * whether its available time in the planned window covers what is still to produce, and which other approved or
     * running orders are planned on it at the same time (none for equipment that runs work orders side by side, E12).
     */
    private void equipmentChecks(WorkOrder order, BigDecimal remaining, List<Check> checks) {
        CatalogEquipmentView equipment = catalog.findProjectEquipment(order.getProjectId(), order.getEquipmentId()).orElse(null);
        if (equipment == null) {
            checks.add(new Check("equipment", FAIL, "The assigned equipment no longer exists; assign other equipment."));
            return;
        }
        String name = "Equipment " + (equipment.equipmentCode() != null ? equipment.equipmentCode() : equipment.equipmentName());
        if ("inactive".equals(equipment.equipmentStatus())) {
            checks.add(new Check("equipment", FAIL, name + " is inactive; assign other equipment."));
            return;
        }
        if ("maintenance".equals(equipment.equipmentStatus())) {
            checks.add(new Check("equipment", FAIL, name + " is under maintenance."));
            return;
        }
        OffsetDateTime start = order.getPlannedStartAt();
        OffsetDateTime end = order.getPlannedEndAt();
        if (start == null || end == null || !end.isAfter(start)) {
            checks.add(new Check("equipment", WARN, name + " is assigned; set the planned start and end to check its time."));
            return;
        }
        if (Duration.between(start, end).compareTo(Duration.ofDays(366)) > 0) {
            checks.add(new Check("equipment", WARN, "The planned window is longer than 366 days, so " + name + "'s time is not checked."));
            return;
        }

        List<WorkOrder> sameEquipment = workOrderRepository.findAllByEquipmentIdAndDeletedYn(equipment.equipmentId(), NOT_DELETED)
            .stream()
            .filter(other -> !other.getWorkOrderId().equals(order.getWorkOrderId()))
            .toList();
        Changeover changeover = changeover(order, equipment, start, sameEquipment);
        BigDecimal changeoverHours = changeover == null ? BigDecimal.ZERO : changeover.hours();

        EquipmentWindow availability = catalog.equipmentWindow(equipment.equipmentId(), start, end).orElse(null);
        if (availability == null) {
            checks.add(new Check("equipment", FAIL, "The assigned equipment no longer exists; assign other equipment."));
            return;
        }
        String has = plain(availability.availableHours()) + " h available in the planned window"
            + (availability.downtimeHours().signum() > 0 ? " (" + plain(availability.downtimeHours()) + " h down)" : "");
        BigDecimal rate = equipment.capacityPerHour();
        if (rate == null || rate.signum() <= 0) {
            checks.add(new Check("equipment", availability.availableHours().signum() > 0 ? OK : WARN,
                name + " has " + has + "; it has no capacity per hour, so the output is not checked."));
        } else if (remaining == null || remaining.signum() <= 0) {
            checks.add(new Check("equipment", availability.availableHours().signum() > 0 ? OK : WARN, name + " has " + has + "."));
        } else {
            // At the output it really reaches (docs/domain/equipment.md E9); the changeover is not scaled.
            BigDecimal needed = remaining.divide(equipment.effectiveCapacityPerHour(), 2, RoundingMode.UP).add(changeoverHours);
            String with = changeoverHours.signum() > 0 ? " (with " + plain(changeoverHours) + " h changeover)" : "";
            BigDecimal efficiency = equipment.efficiencyPercent();
            String pace = efficiency == null || efficiency.compareTo(BigDecimal.valueOf(100)) == 0 ? ""
                : " at " + plain(efficiency) + "% efficiency";
            checks.add(needed.compareTo(availability.availableHours()) > 0
                ? new Check("equipment", WARN,
                    name + " needs " + plain(needed) + " h" + pace + with + " for " + plain(remaining) + " but has only " + has + ".")
                : new Check("equipment", OK, name + " needs " + plain(needed) + " h" + pace + with + " of the " + has + "."));
        }
        if (changeover != null) {
            checks.add(new Check("changeover", OK, "Follows " + changeover.previous().getWorkOrderNumber() + " on " + name + ": "
                + changeover.minutes() + " min changeover from " + itemCode(changeover.previous().getTargetItemId()) + " to "
                + itemCode(order.getTargetItemId()) + "."));
        }

        Integer limit = equipment.maxConcurrent();
        List<WorkOrder> overlapping = limit == null ? List.<WorkOrder>of() : sameEquipment.stream()
            .filter(other -> WorkOrderServiceImpl.status(other).acceptsRuns())
            .filter(other -> other.getPlannedStartAt() != null && other.getPlannedEndAt() != null
                && other.getPlannedStartAt().isBefore(end) && other.getPlannedEndAt().isAfter(start))
            .sorted(Comparator.comparing(WorkOrder::getPlannedStartAt))
            .toList();
        // Up to a limit, only the orders that fill it at some moment of this window clash (E17).
        boolean limited = limit != null && limit > 1;
        List<String> clashes = (limited ? EquipmentOverlap.full(overlapping, start, end, limit) : overlapping).stream()
            .map(WorkOrder::getWorkOrderNumber)
            .toList();
        if (!clashes.isEmpty()) {
            String listed = String.join(", ", clashes.subList(0, Math.min(3, clashes.size())))
                + (clashes.size() > 3 ? " and " + (clashes.size() - 3) + " more" : "");
            String full = limited ? " runs at most " + limit + " work orders at once and" : "";
            checks.add(new Check("schedule", WARN, name + full + " is also planned for " + listed + " in this window."));
        }
    }

    /**
     * Only for an order that names its crew (docs/domain/worker-calendar.md WC13): each person's shift hours in the planned
     * window, less days off and holidays, with their share when it is less than all (WC40). A warning when someone has no
     * shifts or does not work then, never a refusal, like the equipment's time.
     */
    private void crewChecks(WorkOrder order, List<Check> checks) {
        Map<String, Integer> shares = new LinkedHashMap<>();
        crewRepository.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(order.getWorkOrderId())
            .forEach(member -> shares.put(member.getUserId(), WorkOrderCrewService.shareOf(member)));
        if (shares.isEmpty()) {
            return;
        }
        List<String> crew = List.copyOf(shares.keySet());
        OffsetDateTime start = order.getPlannedStartAt();
        OffsetDateTime end = order.getPlannedEndAt();
        if (start == null || end == null || !end.isAfter(start)) {
            checks.add(new Check("crew", WARN, "The crew is named; set the planned start and end to check their time."));
            return;
        }
        if (Duration.between(start, end).compareTo(WorkOrderCrewService.LONGEST_WINDOW) > 0) {
            checks.add(new Check("crew", WARN, "The planned window is longer than 366 days, so the crew's time is not checked."));
            return;
        }
        Map<String, WorkerTime> times = workerCalendars.workingTime(order.getProjectId(), crew, start, end);
        List<String> parts = new ArrayList<>();
        boolean missing = false;
        for (String userId : crew) {
            WorkerTime one = times.get(userId);
            int share = shares.get(userId);
            String name = share < WorkOrderCrewService.FULL_SHARE ? userId + " " + share + "%" : userId;
            if (one == null || !one.calendarSet()) {
                parts.add(name + " has no shifts set");
                missing = true;
            } else if (one.seconds() == 0) {
                parts.add(name + " does not work in the planned window");
                missing = true;
            } else {
                parts.add(name + (share < WorkOrderCrewService.FULL_SHARE ? " of " : " ") + plain(crewHours(one.seconds())) + " h");
            }
        }
        checks.add(new Check("crew", missing ? WARN : OK, "Crew in the planned window: " + String.join(", ", parts) + "."));
        crewBookings(order, shares, times, start, end, checks);
    }

    /**
     * Crew members whose shares of this and other approved or running orders pass 100% at the same time (WC21, WC38): a
     * warning with their working hours then, or just the order for someone without shifts, whose time is not known.
     */
    private void crewBookings(WorkOrder order, Map<String, Integer> shares, Map<String, WorkerTime> times, OffsetDateTime start,
                              OffsetDateTime end, List<Check> checks) {
        List<WorkOrderCrew> elsewhere = crewRepository.findAllByUserIdIn(shares.keySet()).stream()
            .filter(member -> !member.getWorkOrderId().equals(order.getWorkOrderId()))
            .toList();
        if (elsewhere.isEmpty()) {
            return;
        }
        Map<String, WorkOrder> others = new HashMap<>();
        workOrderRepository.findAllById(elsewhere.stream().map(WorkOrderCrew::getWorkOrderId).collect(Collectors.toSet())).stream()
            .filter(other -> order.getProjectId().equals(other.getProjectId()) && NOT_DELETED.equals(other.getDeletedYn()))
            .filter(other -> {
                WorkOrderStatus status = WorkOrderServiceImpl.status(other);
                return status == WorkOrderStatus.APPROVED || status == WorkOrderStatus.IN_PROGRESS;
            })
            .filter(other -> other.getPlannedStartAt() != null && other.getPlannedEndAt() != null
                && other.getPlannedStartAt().isBefore(end) && other.getPlannedEndAt().isAfter(start))
            .forEach(other -> others.put(other.getWorkOrderId(), other));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> person : shares.entrySet()) {
            String userId = person.getKey();
            List<CrewLoad.Held> held = elsewhere.stream()
                .filter(member -> member.getUserId().equals(userId))
                .map(member -> {
                    WorkOrder other = others.get(member.getWorkOrderId());
                    return other == null ? null : new CrewLoad.Held(other, WorkOrderCrewService.shareOf(member));
                })
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((CrewLoad.Held one) -> one.order().getPlannedStartAt())
                    .thenComparing(one -> one.order().getWorkOrderNumber()))
                .toList();
            WorkerTime time = times.get(userId);
            boolean known = time != null && time.calendarSet();
            CrewLoad.overBooked(person.getValue(), held, start, end, time).forEach((other, seconds) ->
                parts.add(known ? userId + " on " + other.getWorkOrderNumber() + " for " + plain(crewHours(seconds)) + " h"
                    : userId + " on " + other.getWorkOrderNumber()));
        }
        if (!parts.isEmpty()) {
            checks.add(new Check("crew_schedule", WARN, "Also booked at the same time: " + String.join(", ", parts) + "."));
        }
    }

    private static BigDecimal crewHours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(3600), 2, RoundingMode.HALF_UP);
    }

    /** The order planned just before this one on the equipment, and the changeover from what it makes. */
    private record Changeover(WorkOrder previous, int minutes) {
        BigDecimal hours() {
            return BigDecimal.valueOf(minutes).divide(BigDecimal.valueOf(60), 2, RoundingMode.UP);
        }
    }

    /**
     * Only approved, running or completed orders count as the one before (docs/domain/equipment-changeover.md): the one
     * on the same equipment that starts last before this order starts. Null without a target item on either, or when no
     * rule applies.
     */
    private Changeover changeover(WorkOrder order, CatalogEquipmentView equipment, OffsetDateTime start, List<WorkOrder> sameEquipment) {
        if (order.getTargetItemId() == null) {
            return null;
        }
        WorkOrder previous = EquipmentSequence.previous(start, sameEquipment);
        if (previous == null) {
            return null;
        }
        java.util.OptionalInt minutes = catalog.changeoverMinutes(equipment.equipmentId(), previous.getTargetItemId(), order.getTargetItemId());
        return minutes.isPresent() ? new Changeover(previous, minutes.getAsInt()) : null;
    }

    private String itemCode(String itemId) {
        return references.item(itemId).map(Item::getItemCode).orElse(itemId);
    }

    private List<Material> materials(WorkOrder order, BomHeader bom, BigDecimal remaining, List<Check> checks) {
        BomRequirementResponse requirement;
        try {
            // Phantoms expand as the order's run will freeze them: the planned day's revision (docs/domain/multi-level-bom.md P3).
            LocalDate phantomDay = order.getPlannedStartAt() == null ? null
                : projectCalendar.date(order.getProjectId(), order.getPlannedStartAt().toInstant());
            requirement = bomService.requirementsForRun(bom.getBomId(), order.getProjectId(), order.getTargetItemId(), remaining, phantomDay);
        } catch (BusinessException exception) {
            checks.add(new Check("materials", FAIL, "Materials could not be calculated: " + exception.getMessage()));
            return List.of();
        }

        List<Inventory> stock = references.projectStocks(order.getProjectId());
        // Stock allocated to this order is reserved for it, so it counts as available here (docs/domain/stock-allocation.md).
        Map<String, BigDecimal> ownAllocated = new HashMap<>();
        stockAllocationRepository.findAllByWorkOrderIdAndStatusOrderByCreatedAtAscAllocationIdAsc(order.getWorkOrderId(), "open")
            .forEach(allocation -> ownAllocated.merge(allocation.getInventoryId(), allocation.remaining(), BigDecimal::add));
        Set<String> lotIds = new HashSet<>();
        stock.forEach(row -> {
            if (row.getLotId() != null) {
                lotIds.add(row.getLotId());
            }
        });
        Map<String, String> lotStatus = new HashMap<>();
        Set<String> expiredLots = new HashSet<>();
        Map<String, LotMaster> lotsById = new HashMap<>();
        LocalDate today = projectCalendar.today(order.getProjectId());
        references.lots(lotIds).forEach(lot -> {
            lotsById.put(lot.getLotId(), lot);
            lotStatus.put(lot.getLotId(), lot.getLotStatus() == null ? "" : lot.getLotStatus());
            if (lot.isExpiredOn(today)) {
                expiredLots.add(lot.getLotId());
            }
        });
        // Usable LOTs that expire within the warning window: counted, but worth using first.
        Map<String, String> expiringSoon = new java.util.LinkedHashMap<>();

        List<Material> materials = new ArrayList<>();
        List<String> shortages = new ArrayList<>();
        // What this order's unfinished runs already put in has left stock, so it is not needed again.
        Map<String, BigDecimal> alreadyUsed = openRunInputs.forOrder(order.getWorkOrderId());
        for (BomRequirementResponse.Line line : requirement.lines()) {
            Item item = references.item(line.childItemId()).orElse(null);
            boolean lotTracked = item != null && "Y".equals(item.getLotManageYn());
            BigDecimal available = BigDecimal.ZERO;
            Set<String> usableLots = new HashSet<>();
            for (Inventory row : stock) {
                if (!line.childItemId().equals(row.getItemId()) || "quarantined".equals(row.getInventoryStatus())) {
                    continue;
                }
                if (row.getLotId() != null && ("closed".equals(lotStatus.get(row.getLotId())) || expiredLots.contains(row.getLotId()))) {
                    continue;
                }
                BigDecimal free = zeroIfNull(row.getQuantity()).subtract(zeroIfNull(row.getReservedQuantity()))
                    .add(ownAllocated.getOrDefault(row.getInventoryId(), BigDecimal.ZERO));
                if (free.signum() > 0) {
                    available = available.add(free);
                    if (row.getLotId() != null) {
                        usableLots.add(row.getLotId());
                        LotMaster lot = lotsById.get(row.getLotId());
                        if (lot != null && lot.getExpiryDate() != null && !lot.getExpiryDate().isAfter(today.plusDays(expiryWarningDays))) {
                            expiringSoon.putIfAbsent(lot.getLotId(), "LOT " + lot.getLotNo() + " of "
                                + (item != null ? item.getItemCode() : line.childItemId()) + " expires on " + lot.getExpiryDate());
                        }
                    }
                }
            }
            BigDecimal required = line.requiredItemQuantity().subtract(alreadyUsed.getOrDefault(line.childItemId(), BigDecimal.ZERO))
                .max(BigDecimal.ZERO);
            BigDecimal shortage = required.subtract(available).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
            String code = item != null ? item.getItemCode() : line.childItemId();
            if (shortage.signum() > 0) {
                shortages.add(code + " (need " + plain(required) + " " + line.itemUnit() + ", available " + plain(available) + ")");
            }
            materials.add(new Material(line.childItemId(), code, item != null ? item.getItemName() : null, required,
                line.itemUnit(), available, shortage, lotTracked, lotTracked ? usableLots.size() : 0));
        }
        checks.add(shortages.isEmpty()
            ? new Check("materials", OK, "Materials for " + plain(remaining) + " are in stock.")
            : new Check("materials", FAIL, "Short of " + String.join(", ", shortages) + "."));
        if (!expiringSoon.isEmpty()) {
            List<String> soon = new ArrayList<>(expiringSoon.values());
            String listed = String.join("; ", soon.subList(0, Math.min(3, soon.size())))
                + (soon.size() > 3 ? "; and " + (soon.size() - 3) + " more" : "");
            checks.add(new Check("expiry", WARN, listed + ". Use these first."));
        }
        // What the remaining quantity's materials cost at today's unit costs (docs/domain/material-cost.md).
        checks.add(new Check("cost", OK, "Estimated material cost: " + plain(requirement.materialCost())
            + (requirement.costComplete() ? "." : " (some materials have no unit cost, so they are left out).")));
        return materials;
    }

    /** Target minus what finished runs of this order produced; null without a target quantity. */
    private BigDecimal remainingQuantity(WorkOrder order) {
        if (order.getTargetQuantity() == null) {
            return null;
        }
        BigDecimal produced = productionRunRepository.findAllByWorkOrderIdInAndDeletedYn(List.of(order.getWorkOrderId()), NOT_DELETED)
            .stream()
            .filter(run -> run.affectsPhysicalState() && "finished".equalsIgnoreCase(run.getRunStatus()))
            .map(ProductionRun::getActualOutputQty)
            .filter(qty -> qty != null)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        return order.getTargetQuantity().subtract(produced);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String plain(BigDecimal value) {
        return zeroIfNull(value).stripTrailingZeros().toPlainString();
    }
}
