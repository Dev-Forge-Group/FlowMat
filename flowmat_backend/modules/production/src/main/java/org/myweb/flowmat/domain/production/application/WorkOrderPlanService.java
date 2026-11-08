package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogEquipmentView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.application.publicapi.EquipmentSlot;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderBatchPlanRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderBatchPlanResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderLaborComparisonResponse;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderPlanSuggestionResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.workflow.application.publicapi.WorkflowProcessOrder;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Suggests a work order's planned start and end from its equipment's calendar (docs/domain/equipment-schedule.md
 * "계획 기간 제안"): the earliest stretch from a given time that holds what is still to produce at the equipment's
 * capacity per hour, plus the changeover from the order before it, and does not overlap an approved or running order
 * planned on the same equipment, unless the equipment runs work orders side by side (docs/domain/equipment.md E12). Read only, like readiness; the order is not changed. Several orders can be planned in
 * one go, each after the ones before it (P8-P12). What is left of the order's step plan, done by its crew at their shares while
 * they work, takes the place of production when it takes longer, and places an order without equipment in its crew's time
 * (docs/domain/worker-calendar.md LB25, LB27).
 */
@Service
@RequiredArgsConstructor
public class WorkOrderPlanService {

    private static final String NOT_DELETED = "N";
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);
    private static final int MAX_BATCH = 50;
    private static final Duration SEARCH_WINDOW = Duration.ofDays(366);

    private final WorkOrderRepository workOrderRepository;
    private final ProductionRunRepository productionRunRepository;
    private final CatalogQuery catalogQuery;
    private final ProjectAccess projectAccessService;
    private final WorkOrderCrewRepository crewRepository;
    private final WorkerCalendarQuery workerCalendars;
    private final ProjectCalendarQuery projectCalendar;
    private final WorkOrderLaborComparisonService laborComparison;
    private final WorkflowProcessOrder processOrder;

    public WorkOrderPlanSuggestionResponse suggest(String workOrderId, String from) {
        WorkOrder order = workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(order.getProjectId());
        OffsetDateTime requested = parseFrom(from);
        return place(order, requested != null ? requested : defaultStart(order), Set.of(order.getWorkOrderId()), List.of());
    }

    /**
     * Suggestions for several orders in the order given (P8-P11). Each is placed like a single suggestion, except that the
     * orders of the request do not count where they stand now (they are being planned again), and each one placed counts
     * as approved work in its suggested dates for the ones after it, changeover included. An order that cannot be placed
     * says why; the others go on.
     */
    public WorkOrderBatchPlanResponse suggestAll(WorkOrderBatchPlanRequest request) {
        List<WorkOrder> orders = batchOrders(request);
        OffsetDateTime requested = parseFrom(request.from());
        Set<String> batch = orders.stream().map(WorkOrder::getWorkOrderId).collect(Collectors.toUnmodifiableSet());
        List<WorkOrder> placed = new ArrayList<>();
        List<WorkOrderBatchPlanResponse.Line> lines = new ArrayList<>();
        for (WorkOrder order : orders) {
            try {
                WorkOrderPlanSuggestionResponse suggestion =
                    place(order, requested != null ? requested : defaultStart(order), batch, placed);
                placed.add(asPlanned(order, suggestion));
                lines.add(new WorkOrderBatchPlanResponse.Line(order.getWorkOrderId(), order.getWorkOrderNumber(),
                    order.getWorkOrderStatus(), suggestion, null));
            } catch (BusinessException refused) {
                if (refused.getErrorCode() != ErrorCode.CONFLICT) {
                    throw refused;
                }
                lines.add(new WorkOrderBatchPlanResponse.Line(order.getWorkOrderId(), order.getWorkOrderNumber(),
                    order.getWorkOrderStatus(), null, refused.getMessage()));
            }
        }
        return new WorkOrderBatchPlanResponse(requested, List.copyOf(lines));
    }

    /**
     * The orders of a batch request in the order given, after the project read check (P8): 1 to 50 distinct orders of the
     * project. The order suggestion (P13) takes the same request.
     */
    List<WorkOrder> batchOrders(WorkOrderBatchPlanRequest request) {
        String projectId = request == null ? null : ProductionText.trimToNull(request.projectId(), "projectId");
        if (projectId == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        projectAccessService.requireProjectReadAccess(projectId);
        List<String> ids = request.workOrderIds() == null ? List.of() : request.workOrderIds();
        if (ids.isEmpty() || ids.size() > MAX_BATCH) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Choose 1 to " + MAX_BATCH + " work orders.");
        }
        if (ids.stream().anyMatch(id -> id == null || id.isBlank()) || new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Name each work order once.");
        }
        List<WorkOrder> orders = new ArrayList<>();
        for (String id : ids) {
            orders.add(workOrderRepository.findByWorkOrderIdAndDeletedYn(id, NOT_DELETED)
                .filter(order -> projectId.equals(order.getProjectId()))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)));
        }
        return orders;
    }

    /**
     * Places one order from {@code start} (P1-P6). Orders in {@code excluded} do not count where they stand; orders in
     * {@code placed} (suggested earlier in a batch, not saved) count as planned work on their equipment.
     */
    private WorkOrderPlanSuggestionResponse place(WorkOrder order, OffsetDateTime start, Set<String> excluded,
                                                  List<WorkOrder> placed) {
        WorkOrderStatus status = WorkOrderServiceImpl.status(order);
        if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED) {
            throw conflict(order.getWorkOrderNumber() + " is " + order.getWorkOrderStatus() + "; it is not planned any more.");
        }
        if (order.getEquipmentId() == null) {
            // Without equipment, a step plan and its crew can still say how long it takes (LB25).
            return placeByCrew(order, start, excluded, placed);
        }
        CatalogEquipmentView equipment = catalogQuery.findProjectEquipment(order.getProjectId(), order.getEquipmentId())
            .orElseThrow(() -> conflict("The assigned equipment no longer exists; assign other equipment."));
        String name = "Equipment " + equipment.label();
        if ("inactive".equals(equipment.equipmentStatus())) {
            throw conflict(name + " is inactive; assign other equipment.");
        }
        BigDecimal rate = equipment.capacityPerHour();
        if (rate == null || rate.signum() <= 0) {
            throw conflict(name + " has no capacity per hour, so how long the order takes is unknown.");
        }
        BigDecimal remaining = remainingQuantity(order);
        if (remaining == null) {
            throw conflict("Set a target quantity first.");
        }
        if (remaining.signum() <= 0) {
            throw conflict("The target quantity has already been produced.");
        }
        // At the output it really reaches (E9); the changeover added below is not scaled.
        BigDecimal productionHours = remaining.divide(equipment.effectiveCapacityPerHour(), 2, RoundingMode.UP);

        List<WorkOrder> others = Stream.concat(
                workOrderRepository.findAllByEquipmentIdAndDeletedYn(equipment.equipmentId(), NOT_DELETED).stream()
                    .filter(other -> !excluded.contains(other.getWorkOrderId())),
                placed.stream().filter(other -> equipment.equipmentId().equals(other.getEquipmentId())))
            .toList();
        // Drafts are not planned work yet, as in readiness's "schedule" check. Equipment that runs work orders side by side
        // does not keep this one apart from them (E12) unless it is full (E17); its crew still does.
        Integer limit = equipment.maxConcurrent();
        List<WorkOrder> planned = limit == null ? List.of() : others.stream()
            .filter(other -> WorkOrderServiceImpl.status(other).acceptsRuns())
            .filter(other -> other.getPlannedStartAt() != null && other.getPlannedEndAt() != null
                && other.getPlannedEndAt().isAfter(other.getPlannedStartAt()))
            .toList();
        CrewPlan crewPlan = crewPlan(order, excluded, placed);
        LaborLeft laborLeft = laborMinutesLeft(order);
        Integer laborMinutes = laborLeft == null ? null : laborLeft.total();
        List<String> movedPast = new ArrayList<>();
        List<WorkOrderPlanSuggestionResponse.CrewBooking> crewMovedPast = new ArrayList<>();
        OffsetDateTime cursor = start;
        // Every round moves past at least one planned order, one booking of the crew or settles the changeover.
        for (int round = 0; round <= 2 * (planned.size() + crewPlan.bookings().size()) + 2; round++) {
            BigDecimal changeoverHours = changeoverHours(order, equipment, EquipmentSequence.previous(cursor, others));
            OffsetDateTime searchFrom = cursor;
            Map<String, WorkerTime> crewTime = crewPlan.userIds().isEmpty() ? Map.of()
                : workerCalendars.workingTime(order.getProjectId(), crewPlan.userIds(), searchFrom, searchFrom.plus(SEARCH_WINDOW));
            // Only people needed for all of their time fix when the order runs (WC39).
            List<String> shiftCrew = fullTime(crewPlan, crewTime);
            List<Stretch> together = shiftCrew.isEmpty() && laborMinutes == null ? List.of()
                : allWorking(equipmentTime(equipment.equipmentId(), searchFrom), shiftCrew, crewTime);
            // The equipment's time, or the step plan's labor after the changeover, done by the crew at their shares while they
            // work, when that takes longer (LB25, LB27).
            Labor labor = laborMinutes == null ? null
                : labor(order, laborLeft, together, seconds(changeoverHours), crewPlan, crewTime, searchFrom);
            BigDecimal laborHours = labor == null ? null : labor.hours();
            BigDecimal needed = (laborHours != null && laborHours.compareTo(productionHours) > 0 ? laborHours : productionHours)
                .add(changeoverHours);
            EquipmentSlot slot = (shiftCrew.isEmpty()
                    ? catalogQuery.earliestSlot(equipment.equipmentId(), searchFrom, seconds(needed))
                    : accumulate(order.getProjectId(), together, seconds(needed)))
                .orElseThrow(() -> conflict(shiftCrew.isEmpty()
                    ? name + " does not have " + plain(needed) + " h available in the 366 days from " + searchFrom + "."
                    : name + " and the crew do not have " + plain(needed) + " h available together in the 366 days from "
                        + searchFrom + "."));
            // The order before the slot can differ from the one before the search start; its changeover then decides.
            WorkOrder before = EquipmentSequence.previous(slot.start(), others);
            if (changeoverHours(order, equipment, before).compareTo(changeoverHours) != 0) {
                cursor = slot.start();
                continue;
            }
            OffsetDateTime end = wholeMinute(slot.end());
            // One at a time, every overlap clashes; up to a limit, the orders that fill it first (E17).
            List<WorkOrder> clashes = limit == null || limit == 1 ? planned.stream()
                .filter(other -> other.getPlannedStartAt().isBefore(end) && other.getPlannedEndAt().isAfter(slot.start()))
                .sorted(Comparator.comparing(WorkOrder::getPlannedStartAt))
                .toList() : EquipmentOverlap.full(planned, slot.start(), end, limit);
            Map<CrewBookingOf, List<String>> crewClashes = crewClashes(crewPlan, slot.start(), end, crewTime);
            if (clashes.isEmpty() && crewClashes.isEmpty()) {
                List<String> waitedFor = shiftCrew.isEmpty() ? List.of()
                    : waitedFor(equipment.equipmentId(), searchFrom, seconds(needed), slot, shiftCrew, crewTime);
                return new WorkOrderPlanSuggestionResponse(order.getWorkOrderId(), equipment.equipmentId(), start, slot.start(),
                    end, remaining, rate, productionHours, changeoverHours, needed,
                    changeoverHours.signum() > 0 ? before.getWorkOrderNumber() : null, List.copyOf(movedPast),
                    crewHours(order.getProjectId(), crewPlan, slot.start(), end), waitedFor, List.copyOf(crewMovedPast),
                    equipment.efficiencyPercent(), laborMinutes, laborHours, scheduleIn(labor, slot, shiftCrew.isEmpty(), together));
            }
            // Up to a limit there is room again when the first of the orders that fill it ends (E17).
            List<WorkOrder> waited = limit != null && limit > 1 ? EquipmentOverlap.firstToEnd(clashes) : clashes;
            waited.stream()
                .map(WorkOrder::getWorkOrderNumber)
                .filter(number -> !movedPast.contains(number))
                .forEach(movedPast::add);
            for (Map.Entry<CrewBookingOf, List<String>> clash : crewClashes.entrySet()) {
                String number = clash.getKey().order().getWorkOrderNumber();
                if (crewMovedPast.stream().noneMatch(one -> one.workOrderNumber().equals(number))) {
                    crewMovedPast.add(new WorkOrderPlanSuggestionResponse.CrewBooking(number, clash.getValue()));
                }
            }
            cursor = Stream.concat(waited.stream().map(WorkOrder::getPlannedEndAt),
                    crewClashes.keySet().stream().map(booking -> booking.order().getPlannedEndAt()))
                .max(Comparator.naturalOrder())
                .orElseThrow();
        }
        throw conflict("No free time found on " + name + " around the orders already planned on it.");
    }

    /**
     * Places an order without equipment by its step plan (docs/domain/worker-calendar.md LB25, LB27): what is left of the plan,
     * done by the crew with shifts at their shares while they work, in the time everyone on it for all of their time works (WC24,
     * WC39) or, without such a person, whenever one of the crew with shifts works, past the other orders that hold them (WC25).
     * Without a step plan or a crew the dates follow equipment, as before.
     */
    private WorkOrderPlanSuggestionResponse placeByCrew(WorkOrder order, OffsetDateTime start, Set<String> excluded,
                                                        List<WorkOrder> placed) {
        LaborLeft laborLeft = laborMinutesLeft(order);
        Integer laborMinutes = laborLeft == null ? null : laborLeft.total();
        CrewPlan crewPlan = crewPlan(order, excluded, placed);
        if (laborMinutes == null || crewPlan.userIds().isEmpty()) {
            throw conflict("Assign equipment first; the dates follow its calendar.");
        }
        if (laborMinutes == 0) {
            throw conflict("The step plan of " + order.getWorkOrderNumber() + " is already worked; nothing is left to plan.");
        }
        BigDecimal remaining = remainingQuantity(order);
        if (remaining != null && remaining.signum() <= 0) {
            throw conflict("The target quantity has already been produced.");
        }
        List<WorkOrderPlanSuggestionResponse.CrewBooking> crewMovedPast = new ArrayList<>();
        OffsetDateTime cursor = start;
        // Every round moves past at least one booking of the crew.
        for (int round = 0; round <= 2 * crewPlan.bookings().size() + 2; round++) {
            OffsetDateTime searchFrom = cursor;
            Map<String, WorkerTime> crewTime = workerCalendars.workingTime(order.getProjectId(), crewPlan.userIds(), searchFrom,
                searchFrom.plus(SEARCH_WINDOW));
            List<String> withShifts = withShifts(crewPlan, crewTime);
            if (withShifts.isEmpty()) {
                throw conflict("The crew of " + order.getWorkOrderNumber() + " has no one with shifts, so when it runs is unknown;"
                    + " assign equipment or set their shifts.");
            }
            // Everyone on it for all of their time fixes when it runs (WC39); without such a person, any of them working (LB27).
            List<String> shiftCrew = fullTime(crewPlan, crewTime);
            List<Stretch> together = shiftCrew.isEmpty() ? anyWorking(withShifts, crewTime)
                : allWorking(List.of(new Stretch(searchFrom.toInstant(), searchFrom.plus(SEARCH_WINDOW).toInstant())), shiftCrew,
                    crewTime);
            Labor labor = labor(order, laborLeft, together, 0, crewPlan, crewTime, searchFrom);
            BigDecimal laborHours = labor.hours();
            EquipmentSlot slot = accumulate(order.getProjectId(), together, seconds(laborHours))
                .orElseThrow(() -> conflict("The crew does not have " + plain(laborHours) + " h available together in the 366 days from "
                    + searchFrom + "."));
            OffsetDateTime end = wholeMinute(slot.end());
            Map<CrewBookingOf, List<String>> crewClashes = crewClashes(crewPlan, slot.start(), end, crewTime);
            if (crewClashes.isEmpty()) {
                return new WorkOrderPlanSuggestionResponse(order.getWorkOrderId(), null, start, slot.start(), end, remaining, null,
                    null, BigDecimal.ZERO, laborHours, null, List.of(), crewHours(order.getProjectId(), crewPlan, slot.start(), end),
                    List.of(), List.copyOf(crewMovedPast), null, laborMinutes, laborHours, scheduleIn(labor, slot, false, together));
            }
            for (Map.Entry<CrewBookingOf, List<String>> clash : crewClashes.entrySet()) {
                String number = clash.getKey().order().getWorkOrderNumber();
                if (crewMovedPast.stream().noneMatch(one -> one.workOrderNumber().equals(number))) {
                    crewMovedPast.add(new WorkOrderPlanSuggestionResponse.CrewBooking(number, clash.getValue()));
                }
            }
            cursor = crewClashes.keySet().stream().map(booking -> booking.order().getPlannedEndAt())
                .max(Comparator.naturalOrder())
                .orElseThrow();
        }
        throw conflict("No free time found for the crew of " + order.getWorkOrderNumber() + " around the orders already holding them.");
    }

    /**
     * What is left of the order's step plan in worker minutes (LB25): each planned process less what its runs recorded on it,
     * never below 0; a process split among people (LB29) is each person's part less what they recorded on it, which others' time
     * on it does not do (LB33). Null without a step plan.
     */
    private LaborLeft laborMinutesLeft(WorkOrder order) {
        WorkOrderLaborComparisonResponse labor = laborComparison.compare(order.getWorkOrderId());
        if (labor.stepPlannedMinutes() == null) {
            return null;
        }
        int total = 0;
        Map<String, Integer> own = new LinkedHashMap<>();
        List<ProcessLeft> processes = new ArrayList<>();
        for (WorkOrderLaborComparisonResponse.Step step : labor.steps()) {
            if (step.plannedMinutes() == null) {
                continue;
            }
            List<WorkOrderLaborComparisonResponse.StepPerson> split = step.people() == null ? List.of()
                : step.people().stream().filter(person -> person.plannedMinutes() != null).toList();
            if (split.isEmpty()) {
                int left = Math.max(0, step.plannedMinutes() - step.actualMinutes());
                total += left;
                processes.add(new ProcessLeft(step.nodeId(), step.nodeName(), left, Map.of()));
                continue;
            }
            Map<String, Integer> parts = new LinkedHashMap<>();
            for (WorkOrderLaborComparisonResponse.StepPerson person : split) {
                int part = Math.max(0, person.plannedMinutes() - person.actualMinutes());
                total += part;
                own.merge(person.userId(), part, Integer::sum);
                parts.merge(person.userId(), part, Integer::sum);
            }
            processes.add(new ProcessLeft(step.nodeId(), step.nodeName(), 0, parts));
        }
        // Which process waits for which (LB34), read only when someone has a part, the only time it changes the end.
        Map<String, Set<String>> before = order.getWorkflowId() != null && own.values().stream().anyMatch(part -> part > 0)
            ? processOrder.processPredecessors(order.getWorkflowId()) : Map.of();
        return new LaborLeft(total, own, processes, before);
    }

    /**
     * @param own       the parts of split processes left to each person they name, within {@code total} (LB33)
     * @param processes each planned process with what is left of it, in the comparison's order
     * @param before    the processes each process waits for (LB34); empty when no one has a part
     */
    private record LaborLeft(int total, Map<String, Integer> own, List<ProcessLeft> processes, Map<String, Set<String>> before) {
    }

    /** @param own what is left to each person it is split among; empty when it is not split */
    private record ProcessLeft(String nodeId, String nodeName, int shared, Map<String, Integer> own) {
    }

    /** The step plan's labor and, for parts of split processes, who works on which process when (LB35). */
    private record Labor(BigDecimal hours, List<WorkOrderPlanSuggestionResponse.Turn> schedule) {
    }

    /**
     * How much of {@code together}, after its first {@code skip} seconds, the step plan's {@code minutes} take (LB27): at each
     * moment the crew members with shifts who work then do their shares of a worker minute a minute, so kim for all of their time
     * and lee for half of theirs do 1.5 while both work and 1 while only kim does. Rounded up to 0.01 h; null when no one on the
     * crew has shifts. People without shifts are not counted, so the time is not made shorter by work no one has time for. Parts of
     * split processes go to their people first (LB33, {@link #splitHours}).
     */
    private static Labor labor(WorkOrder order, LaborLeft rest, List<Stretch> together, long skip, CrewPlan crewPlan,
                                         Map<String, WorkerTime> crewTime, OffsetDateTime from) {
        List<String> withShifts = withShifts(crewPlan, crewTime);
        if (withShifts.isEmpty()) {
            return null;
        }
        // A part of a split process is its person's own only when they are on the crew with shifts; the crew shares the rest (LB33).
        if (rest.own().entrySet().stream().anyMatch(part -> part.getValue() > 0 && withShifts.contains(part.getKey()))) {
            return splitHours(order, rest, together, skip, crewPlan, crewTime, withShifts, from);
        }
        int minutes = rest.total();
        // Someone starting work adds their share, stopping takes it away again.
        List<ShareChange> changes = withShifts.stream()
            .flatMap(userId -> crewTime.get(userId).spans().stream().flatMap(span -> Stream.of(
                new ShareChange(span.start(), crewPlan.shares().get(userId)),
                new ShareChange(span.end(), -crewPlan.shares().get(userId)))))
            .sorted(Comparator.comparing(ShareChange::at))
            .toList();
        // Counted in share seconds: a worker minute is 60 seconds at 100%.
        long left = minutes * 60L * WorkOrderCrewService.FULL_SHARE;
        long skipping = skip;
        long counted = 0;
        int next = 0;
        int share = 0;
        for (Stretch stretch : together) {
            Instant at = stretch.start();
            while (at.isBefore(stretch.end())) {
                while (next < changes.size() && !changes.get(next).at().isAfter(at)) {
                    share += changes.get(next++).share();
                }
                Instant until = next < changes.size() && changes.get(next).at().isBefore(stretch.end())
                    ? changes.get(next).at() : stretch.end();
                long length = Duration.between(at, until).getSeconds();
                if (skipping > 0) {
                    // The changeover comes first.
                    long skipped = Math.min(skipping, length);
                    skipping -= skipped;
                    at = skipped == length ? until : at.plusSeconds(skipped);
                    continue;
                }
                if (share > 0 && share * length >= left) {
                    return new Labor(BigDecimal.valueOf(counted + (left + share - 1) / share).divide(SECONDS_PER_HOUR, 2, RoundingMode.UP),
                        List.of());
                }
                left -= share * length;
                counted += length;
                at = until;
            }
        }
        throw conflict("The crew of " + order.getWorkOrderNumber() + " cannot work the " + minutes
            + " worker min left of its step plan in the 366 days from " + from + ".");
    }

    /**
     * {@link #labor} with parts of split processes (LB33) in the order of the processes (LB34). A process can be worked once
     * every process before it is done; one without a plan is done as soon as they are. At each moment each person working takes
     * their own part of the first process that can be worked, or else the shared minutes of the first such process, at their share;
     * with neither, they wait. The time ends when everything is done.
     */
    private static Labor splitHours(WorkOrder order, LaborLeft rest, List<Stretch> together, long skip, CrewPlan crewPlan,
                                         Map<String, WorkerTime> crewTime, List<String> withShifts, OffsetDateTime from) {
        record Change(Instant at, String userId, int by) {
        }
        List<Change> changes = withShifts.stream()
            .flatMap(userId -> crewTime.get(userId).spans().stream().flatMap(span -> Stream.of(
                new Change(span.start(), userId, 1), new Change(span.end(), userId, -1))))
            .sorted(Comparator.comparing(Change::at))
            .toList();
        // Counted in share seconds, as laborHours does; the part of someone not on the crew with shifts is the crew's (LB33).
        long minute = 60L * WorkOrderCrewService.FULL_SHARE;
        Map<String, Long> shared = new LinkedHashMap<>();
        Map<String, Map<String, Long>> own = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (ProcessLeft process : rest.processes()) {
            names.put(process.nodeId(), process.nodeName());
            long common = process.shared() * minute;
            Map<String, Long> parts = own.computeIfAbsent(process.nodeId(), id -> new LinkedHashMap<>());
            for (Map.Entry<String, Integer> part : process.own().entrySet()) {
                if (withShifts.contains(part.getKey())) {
                    parts.merge(part.getKey(), part.getValue() * minute, Long::sum);
                } else {
                    common += part.getValue() * minute;
                }
            }
            shared.merge(process.nodeId(), common, Long::sum);
        }
        List<String> sequence = List.copyOf(shared.keySet());
        Map<String, Set<String>> before = withoutCycles(rest.before());
        Map<String, Integer> working = new LinkedHashMap<>();
        Map<List<String>, Instant[]> turns = new LinkedHashMap<>();
        long skipping = skip;
        long counted = 0;
        int next = 0;
        for (Stretch stretch : together) {
            Instant at = stretch.start();
            while (at.isBefore(stretch.end())) {
                while (next < changes.size() && !changes.get(next).at().isAfter(at)) {
                    working.merge(changes.get(next).userId(), changes.get(next++).by(), Integer::sum);
                }
                Instant until = next < changes.size() && changes.get(next).at().isBefore(stretch.end())
                    ? changes.get(next).at() : stretch.end();
                long length = Duration.between(at, until).getSeconds();
                if (skipping > 0) {
                    // The changeover comes first.
                    long skipped = Math.min(skipping, length);
                    skipping -= skipped;
                    at = skipped == length ? until : at.plusSeconds(skipped);
                    continue;
                }
                // The processes that can be worked now, in order.
                Map<String, Boolean> complete = new LinkedHashMap<>();
                List<String> open = sequence.stream()
                    .filter(nodeId -> workLeft(nodeId, shared, own) > 0 && ready(nodeId, before, shared, own, complete))
                    .toList();
                String sharedOn = open.stream().filter(nodeId -> shared.get(nodeId) > 0).findFirst().orElse(null);
                // Up to the next moment a part or a process's shared minutes are done, or who works changes.
                long step = length;
                Map<String, String> ownOn = new LinkedHashMap<>();
                List<String> sharing = new ArrayList<>();
                long sharedRate = 0;
                for (String userId : withShifts) {
                    if (working.getOrDefault(userId, 0) <= 0) {
                        continue;
                    }
                    int share = crewPlan.shares().get(userId);
                    String mine = open.stream().filter(nodeId -> own.get(nodeId).getOrDefault(userId, 0L) > 0).findFirst().orElse(null);
                    if (mine != null) {
                        ownOn.put(userId, mine);
                        step = Math.min(step, (own.get(mine).get(userId) + share - 1) / share);
                    } else if (sharedOn != null) {
                        sharing.add(userId);
                        sharedRate += share;
                    }
                }
                if (sharedRate > 0) {
                    step = Math.min(step, (shared.get(sharedOn) + sharedRate - 1) / sharedRate);
                }
                // Who does what when, for the schedule (LB35).
                for (Map.Entry<String, String> mine : ownOn.entrySet()) {
                    turn(turns, mine.getValue(), mine.getKey(), at, step);
                }
                for (String userId : sharing) {
                    turn(turns, sharedOn, userId, at, step);
                }
                for (Map.Entry<String, String> mine : ownOn.entrySet()) {
                    Map<String, Long> parts = own.get(mine.getValue());
                    parts.put(mine.getKey(), Math.max(0, parts.get(mine.getKey()) - crewPlan.shares().get(mine.getKey()) * step));
                }
                if (sharedRate > 0) {
                    shared.put(sharedOn, Math.max(0, shared.get(sharedOn) - sharedRate * step));
                }
                counted += step;
                at = at.plusSeconds(step);
                if (sequence.stream().allMatch(nodeId -> workLeft(nodeId, shared, own) == 0)) {
                    return new Labor(BigDecimal.valueOf(counted).divide(SECONDS_PER_HOUR, 2, RoundingMode.UP), schedule(turns, names, from));
                }
            }
        }
        throw conflict("The crew of " + order.getWorkOrderNumber() + " cannot work the " + rest.total()
            + " worker min left of its step plan in the 366 days from " + from + ".");
    }

    /** Widens a person's time on a process to take in [at, at + seconds) (LB35). */
    private static void turn(Map<List<String>, Instant[]> turns, String nodeId, String userId, Instant at, long seconds) {
        Instant[] span = turns.computeIfAbsent(List.of(nodeId, userId), key -> new Instant[] {at, at});
        Instant end = at.plusSeconds(seconds);
        if (at.isBefore(span[0])) {
            span[0] = at;
        }
        if (end.isAfter(span[1])) {
            span[1] = end;
        }
    }

    /** Each person's first start and last end on each process, earliest first, at the offset the search was asked in (LB35). */
    private static List<WorkOrderPlanSuggestionResponse.Turn> schedule(Map<List<String>, Instant[]> turns, Map<String, String> names,
                                                                       OffsetDateTime from) {
        return turns.entrySet().stream()
            .map(entry -> new WorkOrderPlanSuggestionResponse.Turn(entry.getKey().get(0), names.get(entry.getKey().get(0)),
                entry.getKey().get(1), entry.getValue()[0].atOffset(from.getOffset()), entry.getValue()[1].atOffset(from.getOffset())))
            .sorted(Comparator.comparing(WorkOrderPlanSuggestionResponse.Turn::from))
            .toList();
    }

    /**
     * The schedule when its times are the slot's: the slot starts where the labor was counted (LB35). A slot of the equipment's own
     * time can start later than that; then there is none.
     */
    private static List<WorkOrderPlanSuggestionResponse.Turn> scheduleIn(Labor labor, EquipmentSlot slot, boolean ownSlot,
                                                                         List<Stretch> together) {
        if (labor == null || labor.schedule().isEmpty()) {
            return List.of();
        }
        if (ownSlot && (together.isEmpty() || !slot.start().toInstant().equals(together.get(0).start()))) {
            return List.of();
        }
        return labor.schedule();
    }

    /** The share seconds left of a process, shared and in parts; 0 for one without a plan. */
    private static long workLeft(String nodeId, Map<String, Long> shared, Map<String, Map<String, Long>> own) {
        return shared.getOrDefault(nodeId, 0L) + own.getOrDefault(nodeId, Map.of()).values().stream().mapToLong(Long::longValue).sum();
    }

    /** Whether every process before this one is done, and so on back (LB34). */
    private static boolean ready(String nodeId, Map<String, Set<String>> before, Map<String, Long> shared,
                                 Map<String, Map<String, Long>> own, Map<String, Boolean> complete) {
        for (String earlier : before.getOrDefault(nodeId, Set.of())) {
            Boolean done = complete.get(earlier);
            if (done == null) {
                done = workLeft(earlier, shared, own) == 0 && ready(earlier, before, shared, own, complete);
                complete.put(earlier, done);
            }
            if (!done) {
                return false;
            }
        }
        return true;
    }

    /** The order of the processes without the connections that close a cycle, found depth first (LB34). */
    private static Map<String, Set<String>> withoutCycles(Map<String, Set<String>> before) {
        Map<String, Set<String>> kept = new LinkedHashMap<>();
        Set<String> finished = new HashSet<>();
        for (String nodeId : before.keySet()) {
            visit(nodeId, before, kept, finished, new HashSet<>());
        }
        return kept;
    }

    private static void visit(String nodeId, Map<String, Set<String>> before, Map<String, Set<String>> kept, Set<String> finished,
                              Set<String> path) {
        if (finished.contains(nodeId)) {
            return;
        }
        path.add(nodeId);
        Set<String> earlier = new HashSet<>();
        for (String one : before.getOrDefault(nodeId, Set.of())) {
            if (path.contains(one)) {
                continue;
            }
            visit(one, before, kept, finished, path);
            earlier.add(one);
        }
        kept.put(nodeId, earlier);
        path.remove(nodeId);
        finished.add(nodeId);
    }

    /** The crew members with shifts. */
    private static List<String> withShifts(CrewPlan crewPlan, Map<String, WorkerTime> crewTime) {
        return crewPlan.userIds().stream()
            .filter(userId -> crewTime.containsKey(userId) && crewTime.get(userId).calendarSet())
            .toList();
    }

    /** The crew members with shifts on the order for all of their time: the people who fix when it runs (WC39). */
    private static List<String> fullTime(CrewPlan crewPlan, Map<String, WorkerTime> crewTime) {
        return withShifts(crewPlan, crewTime).stream()
            .filter(userId -> crewPlan.shares().get(userId) == WorkOrderCrewService.FULL_SHARE)
            .toList();
    }

    /** {@code from} as an instant; null when not given. */
    static OffsetDateTime parseFrom(String from) {
        if (from == null || from.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(from.trim());
        } catch (DateTimeParseException exception) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "from must be a date and time with an offset, such as 2030-01-07T09:00:00+09:00.");
        }
    }

    /** Without {@code from}: the planned start if it is still ahead, or the next whole minute. */
    private static OffsetDateTime defaultStart(WorkOrder order) {
        OffsetDateTime now = wholeMinute(OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime planned = order.getPlannedStartAt();
        return planned != null && planned.isAfter(now) ? planned : now;
    }

    /**
     * The order's crew, each person's shift hours in the suggested window and their share (docs/domain/worker-calendar.md
     * WC14, WC36).
     */
    private List<WorkOrderPlanSuggestionResponse.Crew> crewHours(String projectId, CrewPlan crewPlan, OffsetDateTime start,
                                                                 OffsetDateTime end) {
        if (crewPlan.userIds().isEmpty()) {
            return List.of();
        }
        Map<String, WorkerHours> hours = workerCalendars.workingHours(projectId, crewPlan.userIds(), start, end);
        return crewPlan.userIds().stream()
            .map(userId -> {
                WorkerHours one = hours.get(userId);
                int share = crewPlan.shares().get(userId);
                return one == null ? new WorkOrderPlanSuggestionResponse.Crew(userId, false, BigDecimal.ZERO, share)
                    : new WorkOrderPlanSuggestionResponse.Crew(userId, one.calendarSet(), one.hours(), share);
            })
            .toList();
    }

    /** The crew of the order being placed, their shares, and the approved or running orders that hold some of them (WC25). */
    private record CrewPlan(List<String> userIds, Map<String, Integer> shares, List<CrewBookingOf> bookings) {
    }

    /** Another order that names some of the crew, in the dates it holds them; each person's share of it, in crew order. */
    private record CrewBookingOf(WorkOrder order, Map<String, Integer> shares) {
    }

    private record Stretch(Instant start, Instant end) {
    }

    /** Someone on the crew starting ({@code share} above 0) or stopping (below 0) work at {@code at}. */
    private record ShareChange(Instant at, int share) {
    }

    /**
     * The order's crew and the orders that hold them (WC25): saved approved or running orders of the project with planned
     * dates, except the ones being planned again, plus the ones a batch has already placed, in their suggested dates.
     */
    private CrewPlan crewPlan(WorkOrder order, Set<String> excluded, List<WorkOrder> placed) {
        Map<String, Integer> shares = new LinkedHashMap<>();
        crewRepository.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(order.getWorkOrderId())
            .forEach(member -> shares.put(member.getUserId(), WorkOrderCrewService.shareOf(member)));
        if (shares.isEmpty()) {
            return new CrewPlan(List.of(), Map.of(), List.of());
        }
        List<String> crew = List.copyOf(shares.keySet());
        Map<String, Map<String, Integer>> sharesByOrder = new LinkedHashMap<>();
        for (WorkOrderCrew member : crewRepository.findAllByUserIdIn(crew)) {
            sharesByOrder.computeIfAbsent(member.getWorkOrderId(), id -> new LinkedHashMap<>())
                .put(member.getUserId(), WorkOrderCrewService.shareOf(member));
        }
        sharesByOrder.remove(order.getWorkOrderId());
        List<CrewBookingOf> bookings = new ArrayList<>();
        List<String> saved = sharesByOrder.keySet().stream().filter(id -> !excluded.contains(id)).toList();
        if (!saved.isEmpty()) {
            workOrderRepository.findAllById(saved).stream()
                .filter(other -> order.getProjectId().equals(other.getProjectId()) && NOT_DELETED.equals(other.getDeletedYn()))
                .filter(other -> WorkOrderServiceImpl.status(other).acceptsRuns())
                .filter(WorkOrderPlanService::hasDates)
                .forEach(other -> bookings.add(new CrewBookingOf(other, inCrewOrder(sharesByOrder.get(other.getWorkOrderId()), crew))));
        }
        for (WorkOrder other : placed) {
            Map<String, Integer> shared = sharesByOrder.get(other.getWorkOrderId());
            if (shared != null && hasDates(other)) {
                bookings.add(new CrewBookingOf(other, inCrewOrder(shared, crew)));
            }
        }
        return new CrewPlan(crew, Map.copyOf(shares), List.copyOf(bookings));
    }

    private static Map<String, Integer> inCrewOrder(Map<String, Integer> shares, List<String> crew) {
        Map<String, Integer> ordered = new LinkedHashMap<>();
        crew.stream().filter(shares::containsKey).forEach(userId -> ordered.put(userId, shares.get(userId)));
        return ordered;
    }

    private static boolean hasDates(WorkOrder order) {
        return order.getPlannedStartAt() != null && order.getPlannedEndAt() != null
            && order.getPlannedEndAt().isAfter(order.getPlannedStartAt());
    }

    /**
     * The orders that, with this order's shares, want more than all of some crew member's time in [start, end), each with the
     * people it holds too much (WC25, WC39): working time of theirs falls there, or their time is not known because they have
     * no shifts. Earliest first.
     */
    private static Map<CrewBookingOf, List<String>> crewClashes(CrewPlan crewPlan, OffsetDateTime start, OffsetDateTime end,
                                                                Map<String, WorkerTime> crewTime) {
        List<CrewBookingOf> sorted = crewPlan.bookings().stream()
            .sorted(Comparator.comparing(booking -> booking.order().getPlannedStartAt()))
            .toList();
        Map<CrewBookingOf, List<String>> found = new LinkedHashMap<>();
        for (String userId : crewPlan.userIds()) {
            List<CrewLoad.Held> held = sorted.stream()
                .filter(booking -> booking.shares().containsKey(userId))
                .map(booking -> new CrewLoad.Held(booking.order(), booking.shares().get(userId)))
                .toList();
            if (held.isEmpty()) {
                continue;
            }
            Set<WorkOrder> over = CrewLoad.overBooked(crewPlan.shares().get(userId), held, start, end, crewTime.get(userId)).keySet();
            sorted.stream()
                .filter(booking -> over.contains(booking.order()))
                .forEach(booking -> found.computeIfAbsent(booking, key -> new ArrayList<>()).add(userId));
        }
        Map<CrewBookingOf, List<String>> ordered = new LinkedHashMap<>();
        sorted.stream().filter(found::containsKey).forEach(booking -> ordered.put(booking, List.copyOf(found.get(booking))));
        return ordered;
    }

    /** The equipment's available time in the 366 days from {@code from}: its shifts less holidays and downtime (WC24). */
    private List<Stretch> equipmentTime(String equipmentId, OffsetDateTime from) {
        return catalogQuery.availableSlots(equipmentId, from, from.plus(SEARCH_WINDOW)).orElse(List.of()).stream()
            .map(slot -> new Stretch(slot.start().toInstant(), slot.end().toInstant()))
            .toList();
    }

    /**
     * The part of {@code stretches} in which everyone in {@code userIds} works (WC24); accumulated, the equipment's time with its
     * crew is counted like {@link CatalogQuery#earliestSlot}.
     */
    private static List<Stretch> allWorking(List<Stretch> stretches, List<String> userIds, Map<String, WorkerTime> crewTime) {
        List<Stretch> together = stretches;
        for (String userId : userIds) {
            together = intersect(together, crewTime.get(userId).spans());
        }
        return together;
    }

    /** The time in which at least one of {@code userIds} works, merged, earliest first (LB27). */
    private static List<Stretch> anyWorking(List<String> userIds, Map<String, WorkerTime> crewTime) {
        List<WorkerTime.Span> spans = userIds.stream()
            .flatMap(userId -> crewTime.get(userId).spans().stream())
            .sorted(Comparator.comparing(WorkerTime.Span::start))
            .toList();
        List<Stretch> merged = new ArrayList<>();
        for (WorkerTime.Span span : spans) {
            int last = merged.size() - 1;
            if (last >= 0 && !span.start().isAfter(merged.get(last).end())) {
                if (span.end().isAfter(merged.get(last).end())) {
                    merged.set(last, new Stretch(merged.get(last).start(), span.end()));
                }
            } else {
                merged.add(new Stretch(span.start(), span.end()));
            }
        }
        return merged;
    }

    /** From the first of {@code together}, the stretch in which {@code seconds} of it have passed; empty when they do not hold that. */
    private Optional<EquipmentSlot> accumulate(String projectId, List<Stretch> together, long seconds) {
        ZoneId zone = projectCalendar.zone(projectId);
        Instant first = null;
        long left = seconds;
        for (Stretch stretch : together) {
            if (first == null) {
                first = stretch.start();
            }
            long length = Duration.between(stretch.start(), stretch.end()).getSeconds();
            if (length >= left) {
                return Optional.of(new EquipmentSlot(first.atZone(zone).toOffsetDateTime(),
                    stretch.start().plusSeconds(left).atZone(zone).toOffsetDateTime()));
            }
            left -= length;
        }
        return Optional.empty();
    }

    /** Both lists earliest first and none overlapping within each. */
    private static List<Stretch> intersect(List<Stretch> stretches, List<WorkerTime.Span> spans) {
        List<Stretch> result = new ArrayList<>();
        int one = 0;
        int other = 0;
        while (one < stretches.size() && other < spans.size()) {
            Stretch stretch = stretches.get(one);
            WorkerTime.Span span = spans.get(other);
            Instant start = stretch.start().isAfter(span.start()) ? stretch.start() : span.start();
            Instant end = stretch.end().isBefore(span.end()) ? stretch.end() : span.end();
            if (end.isAfter(start)) {
                result.add(new Stretch(start, end));
            }
            if (stretch.end().isBefore(span.end())) {
                one++;
            } else {
                other++;
            }
        }
        return result;
    }

    /**
     * Crew members with shifts who were not working when the equipment alone could have started: the people the suggestion
     * waited for (WC25). None when the crew did not move the start.
     */
    private List<String> waitedFor(String equipmentId, OffsetDateTime from, long seconds, EquipmentSlot slot,
                                   List<String> shiftCrew, Map<String, WorkerTime> crewTime) {
        Optional<EquipmentSlot> alone = catalogQuery.earliestSlot(equipmentId, from, seconds);
        if (alone.isEmpty() || !slot.start().isAfter(alone.get().start())) {
            return List.of();
        }
        Instant at = alone.get().start().toInstant();
        return shiftCrew.stream()
            .filter(userId -> crewTime.get(userId).secondsBetween(at, at.plusSeconds(1)) == 0)
            .toList();
    }

    /** An order placed in a batch, as approved work in its suggested dates for the orders placed after it (P9). Not saved. */
    private static WorkOrder asPlanned(WorkOrder order, WorkOrderPlanSuggestionResponse suggestion) {
        WorkOrder planned = new WorkOrder();
        planned.setWorkOrderId(order.getWorkOrderId());
        planned.setWorkOrderNumber(order.getWorkOrderNumber());
        planned.setProjectId(order.getProjectId());
        planned.setTargetItemId(order.getTargetItemId());
        planned.setEquipmentId(suggestion.equipmentId());
        planned.setWorkOrderStatus(WorkOrderStatus.APPROVED.code());
        planned.setPlannedStartAt(suggestion.plannedStartAt());
        planned.setPlannedEndAt(suggestion.plannedEndAt());
        return planned;
    }

    /** The changeover from what {@code previous} makes to what this order makes; zero without either item or a rule. */
    private BigDecimal changeoverHours(WorkOrder order, CatalogEquipmentView equipment, WorkOrder previous) {
        if (previous == null || order.getTargetItemId() == null) {
            return BigDecimal.ZERO;
        }
        OptionalInt minutes = catalogQuery.changeoverMinutes(equipment.equipmentId(), previous.getTargetItemId(),
            order.getTargetItemId());
        return minutes.isPresent()
            ? BigDecimal.valueOf(minutes.getAsInt()).divide(MINUTES_PER_HOUR, 2, RoundingMode.UP)
            : BigDecimal.ZERO;
    }

    /** Target quantity minus what finished runs that move stock produced; null without a target quantity. */
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
        return order.getTargetQuantity().subtract(produced).max(BigDecimal.ZERO);
    }

    private static long seconds(BigDecimal hours) {
        return hours.multiply(SECONDS_PER_HOUR).setScale(0, RoundingMode.UP).longValueExact();
    }

    /** Rounded up to the minute, so the end fits a date-time field and still holds the needed time. */
    private static OffsetDateTime wholeMinute(OffsetDateTime time) {
        OffsetDateTime minute = time.truncatedTo(ChronoUnit.MINUTES);
        return minute.equals(time) ? time : minute.plusMinutes(1);
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message);
    }
}
