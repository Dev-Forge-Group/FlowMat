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
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderPlanSuggestionResponse;
import org.myweb.flowmat.domain.production.domain.entity.ProductionRun;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.ProductionRunRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Suggests a work order's planned start and end from its equipment's calendar (docs/domain/equipment-schedule.md
 * "계획 기간 제안"): the earliest stretch from a given time that holds what is still to produce at the equipment's
 * capacity per hour, plus the changeover from the order before it, and does not overlap an approved or running order
 * planned on the same equipment, unless the equipment runs work orders side by side (docs/domain/equipment.md E12). Read only, like readiness; the order is not changed. Several orders can be planned in
 * one go, each after the ones before it (P8-P12).
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
    private final ProjectAccessService projectAccessService;
    private final WorkOrderCrewRepository crewRepository;
    private final WorkerCalendarQuery workerCalendars;
    private final ProjectCalendarQuery projectCalendar;

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
            throw conflict("Assign equipment first; the dates follow its calendar.");
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
        List<String> movedPast = new ArrayList<>();
        List<WorkOrderPlanSuggestionResponse.CrewBooking> crewMovedPast = new ArrayList<>();
        OffsetDateTime cursor = start;
        // Every round moves past at least one planned order, one booking of the crew or settles the changeover.
        for (int round = 0; round <= 2 * (planned.size() + crewPlan.bookings().size()) + 2; round++) {
            BigDecimal changeoverHours = changeoverHours(order, equipment, EquipmentSequence.previous(cursor, others));
            BigDecimal needed = productionHours.add(changeoverHours);
            OffsetDateTime searchFrom = cursor;
            Map<String, WorkerTime> crewTime = crewPlan.userIds().isEmpty() ? Map.of()
                : workerCalendars.workingTime(order.getProjectId(), crewPlan.userIds(), searchFrom, searchFrom.plus(SEARCH_WINDOW));
            // Only people needed for all of their time fix when the order runs (WC39).
            List<String> shiftCrew = crewPlan.userIds().stream()
                .filter(userId -> crewTime.containsKey(userId) && crewTime.get(userId).calendarSet()
                    && crewPlan.shares().get(userId) == WorkOrderCrewService.FULL_SHARE)
                .toList();
            EquipmentSlot slot = (shiftCrew.isEmpty()
                    ? catalogQuery.earliestSlot(equipment.equipmentId(), searchFrom, seconds(needed))
                    : slotWithCrew(order.getProjectId(), equipment.equipmentId(), searchFrom, seconds(needed), shiftCrew, crewTime))
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
                    equipment.efficiencyPercent());
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

    /**
     * The earliest stretch from {@code from} that holds {@code seconds} of time when the equipment is available and everyone
     * on the crew with shifts is working (WC24), counted like {@link CatalogQuery#earliestSlot}. Empty when the 366 days from
     * {@code from} do not hold that much.
     */
    private Optional<EquipmentSlot> slotWithCrew(String projectId, String equipmentId, OffsetDateTime from, long seconds,
                                                 List<String> shiftCrew, Map<String, WorkerTime> crewTime) {
        List<Stretch> together = catalogQuery.availableSlots(equipmentId, from, from.plus(SEARCH_WINDOW)).orElse(List.of()).stream()
            .map(slot -> new Stretch(slot.start().toInstant(), slot.end().toInstant()))
            .toList();
        for (String userId : shiftCrew) {
            together = intersect(together, crewTime.get(userId).spans());
        }
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
