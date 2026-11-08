package org.myweb.flowmat.domain.production.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.response.WorkerLoadResponse;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerTime;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Each worker's shift hours in a window against the work orders that name them (docs/domain/worker-calendar.md WC18-WC20;
 * user decision 2026-10-14 "Worker load"). A person on an order is booked for their working time inside its planned window;
 * approved and running orders count, drafts are shown apart, and two orders at once are listed.
 */
@Service
@RequiredArgsConstructor
public class WorkerLoadService {

    private static final String NOT_DELETED = "N";
    private static final Duration LONGEST_WINDOW = Duration.ofDays(92);
    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3600);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final WorkerTime NO_TIME = new WorkerTime(false, List.of());

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderCrewRepository crewRepository;
    private final ProjectAccessService access;
    private final ProjectMemberQuery members;
    private final WorkerCalendarQuery workerCalendars;

    @Transactional(readOnly = true)
    public WorkerLoadResponse load(String projectId, String from, String to) {
        if (projectId == null || projectId.isBlank()) {
            throw bad("projectId is required.");
        }
        access.requireProjectReadAccess(projectId);
        OffsetDateTime start = dateTime(from, "from");
        OffsetDateTime end = dateTime(to, "to");
        if (!end.isAfter(start)) {
            throw bad("to must be after from.");
        }
        if (Duration.between(start, end).compareTo(LONGEST_WINDOW) > 0) {
            throw bad("The worker load board covers at most 92 days.");
        }
        Map<String, WorkOrder> open = new LinkedHashMap<>();
        for (WorkOrder order : workOrderRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtDesc(projectId, NOT_DELETED)) {
            WorkOrderStatus status = WorkOrderServiceImpl.status(order);
            if (status != WorkOrderStatus.COMPLETED && status != WorkOrderStatus.CANCELLED) {
                open.put(order.getWorkOrderId(), order);
            }
        }
        Map<String, List<CrewLoad.Held>> byWorker = new HashMap<>();
        if (!open.isEmpty()) {
            for (WorkOrderCrew member : crewRepository.findAllByWorkOrderIdIn(open.keySet())) {
                byWorker.computeIfAbsent(member.getUserId(), id -> new ArrayList<>())
                    .add(new CrewLoad.Held(open.get(member.getWorkOrderId()), WorkOrderCrewService.shareOf(member)));
            }
        }
        List<String> workers = members.readers(projectId);
        String owner = members.ownerOf(projectId);
        Map<String, WorkerTime> time = workerCalendars.workingTime(projectId, workers, start, end);
        List<WorkerLoadResponse.Row> rows = workers.stream()
            .map(userId -> row(userId, userId.equals(owner), time.getOrDefault(userId, NO_TIME),
                byWorker.getOrDefault(userId, List.of()), start, end))
            .toList();
        return new WorkerLoadResponse(start, end, rows);
    }

    private static WorkerLoadResponse.Row row(String userId, boolean owner, WorkerTime time, List<CrewLoad.Held> orders,
                                              OffsetDateTime from, OffsetDateTime to) {
        long shift = time.secondsBetween(from.toInstant(), to.toInstant());
        long planned = 0;
        long drafts = 0;
        int unplanned = 0;
        List<WorkerLoadResponse.Order> shown = new ArrayList<>();
        List<CrewLoad.Held> booked = new ArrayList<>();
        List<CrewLoad.Held> sorted = orders.stream()
            .sorted(Comparator.comparing((CrewLoad.Held held) -> held.order().getPlannedStartAt(),
                    Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(held -> held.order().getWorkOrderNumber(), Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();
        for (CrewLoad.Held held : sorted) {
            WorkOrder order = held.order();
            OffsetDateTime start = order.getPlannedStartAt();
            OffsetDateTime end = order.getPlannedEndAt();
            if (start == null || end == null || !end.isAfter(start)) {
                unplanned++;
                continue;
            }
            if (!start.isBefore(to) || !end.isAfter(from)) {
                continue;
            }
            // Their share of their working time there (WC37).
            long seconds = time.secondsBetween(later(start, from).toInstant(), earlier(end, to).toInstant()) * held.share()
                / WorkOrderCrewService.FULL_SHARE;
            if (WorkOrderServiceImpl.status(order) == WorkOrderStatus.DRAFT) {
                drafts += seconds;
            } else {
                planned += seconds;
                booked.add(held);
            }
            shown.add(new WorkerLoadResponse.Order(order.getWorkOrderId(), order.getWorkOrderNumber(), order.getWorkOrderTitle(),
                order.getWorkOrderStatus(), held.share(), start, end, hours(seconds)));
        }
        // Two orders count as at once only where all the shares then pass 100% (WC38).
        List<CrewLoad.Over> overs = CrewLoad.over(0, booked, from.toInstant(), to.toInstant());
        List<WorkerLoadResponse.Overlap> overlaps = new ArrayList<>();
        for (int first = 0; first < booked.size(); first++) {
            for (int second = first + 1; second < booked.size(); second++) {
                CrewLoad.Held oneHeld = booked.get(first);
                CrewLoad.Held otherHeld = booked.get(second);
                WorkOrder one = oneHeld.order();
                WorkOrder other = otherHeld.order();
                OffsetDateTime overlapStart = later(later(one.getPlannedStartAt(), other.getPlannedStartAt()), from);
                OffsetDateTime overlapEnd = earlier(earlier(one.getPlannedEndAt(), other.getPlannedEndAt()), to);
                if (!overlapEnd.isAfter(overlapStart)) {
                    continue;
                }
                long seconds = 0;
                boolean over = false;
                for (CrewLoad.Over stretch : overs) {
                    if (stretch.active().contains(oneHeld) && stretch.active().contains(otherHeld)) {
                        over = true;
                        seconds += time.secondsBetween(stretch.start(), stretch.end());
                    }
                }
                if (seconds > 0 || (over && !time.calendarSet())) {
                    overlaps.add(new WorkerLoadResponse.Overlap(one.getWorkOrderNumber(), other.getWorkOrderNumber(), overlapStart,
                        overlapEnd, hours(seconds)));
                }
            }
        }
        BigDecimal percent = shift > 0
            ? BigDecimal.valueOf(planned).multiply(HUNDRED).divide(BigDecimal.valueOf(shift), 1, RoundingMode.HALF_UP)
            : null;
        return new WorkerLoadResponse.Row(userId, owner, time.calendarSet(), hours(shift), hours(planned), hours(drafts), percent,
            shift > 0 && planned > shift, unplanned, List.copyOf(shown), List.copyOf(overlaps));
    }

    private static OffsetDateTime later(OffsetDateTime one, OffsetDateTime other) {
        return one.isAfter(other) ? one : other;
    }

    private static OffsetDateTime earlier(OffsetDateTime one, OffsetDateTime other) {
        return one.isBefore(other) ? one : other;
    }

    private static BigDecimal hours(long seconds) {
        return BigDecimal.valueOf(seconds).divide(SECONDS_PER_HOUR, 2, RoundingMode.HALF_UP);
    }

    private static OffsetDateTime dateTime(String value, String name) {
        if (value == null || value.isBlank()) {
            throw bad(name + " is required.");
        }
        try {
            return OffsetDateTime.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(name + " must be a date and time with an offset, such as 2030-01-07T00:00:00+09:00.");
        }
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
