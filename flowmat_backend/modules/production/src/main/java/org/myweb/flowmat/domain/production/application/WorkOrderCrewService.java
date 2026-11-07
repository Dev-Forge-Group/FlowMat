package org.myweb.flowmat.domain.production.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.production.api.dto.request.WorkOrderCrewRequest;
import org.myweb.flowmat.domain.production.api.dto.response.WorkOrderCrewResponse;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrder;
import org.myweb.flowmat.domain.production.domain.entity.WorkOrderCrew;
import org.myweb.flowmat.domain.production.domain.enums.WorkOrderStatus;
import org.myweb.flowmat.domain.production.repository.WorkOrderCrewRepository;
import org.myweb.flowmat.domain.production.repository.WorkOrderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerCalendarQuery;
import org.myweb.flowmat.domain.workforce.application.publicapi.WorkerHours;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The people a work order needs (docs/domain/worker-calendar.md WC11-WC12). Like its equipment, the crew can change until the
 * order is completed or cancelled; readiness and plan suggestions read it (WC13-WC14).
 */
@Service
@RequiredArgsConstructor
public class WorkOrderCrewService {

    private static final String NOT_DELETED = "N";
    private static final int MAX_CREW = 20;
    /** A share of all of a person's time in the window (WC36). */
    static final int FULL_SHARE = 100;
    static final Duration LONGEST_WINDOW = Duration.ofDays(366);

    private final WorkOrderRepository workOrderRepository;
    private final WorkOrderCrewRepository crewRepository;
    private final ProjectAccessService access;
    private final ProjectMemberQuery members;
    private final WorkerCalendarQuery workerCalendars;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public WorkOrderCrewResponse crew(String workOrderId) {
        WorkOrder order = workOrderRepository.findByWorkOrderIdAndDeletedYn(workOrderId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(order.getProjectId());
        return response(order);
    }

    @Transactional
    public WorkOrderCrewResponse set(String workOrderId, WorkOrderCrewRequest request) {
        WorkOrder order = workOrderRepository.findForUpdate(workOrderId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(order.getProjectId());
        WorkOrderStatus status = WorkOrderServiceImpl.status(order);
        if (status == WorkOrderStatus.COMPLETED || status == WorkOrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.CONFLICT,
                order.getWorkOrderNumber() + " is " + order.getWorkOrderStatus() + "; its crew can no longer change.");
        }
        Map<String, Integer> wanted = wanted(order.getProjectId(), request);
        Set<String> kept = new HashSet<>();
        for (WorkOrderCrew member : crewRepository.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(order.getWorkOrderId())) {
            Integer share = wanted.get(member.getUserId());
            if (share == null) {
                crewRepository.delete(member);
            } else {
                kept.add(member.getUserId());
                member.setSharePercent(share);
                crewRepository.save(member);
            }
        }
        crewRepository.flush();
        String actor = access.requireCurrentUserId();
        // A microsecond apart, so people named together keep the order they were named in.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        int added = 0;
        for (Map.Entry<String, Integer> one : wanted.entrySet()) {
            if (kept.contains(one.getKey())) {
                continue;
            }
            WorkOrderCrew member = new WorkOrderCrew();
            member.setCrewId(ids.generate());
            member.setWorkOrderId(order.getWorkOrderId());
            member.setUserId(one.getKey());
            member.setSharePercent(one.getValue());
            member.setAddedBy(actor);
            member.setAddedAt(now.plus(added++, ChronoUnit.MICROS));
            crewRepository.save(member);
        }
        crewRepository.flush();
        return response(order);
    }

    /** The people asked for, in order, each with their share (WC12, WC36). */
    private Map<String, Integer> wanted(String projectId, WorkOrderCrewRequest request) {
        if (request != null && request.userIds() != null && request.members() != null) {
            throw bad("Send userIds or members, not both.");
        }
        List<WorkOrderCrewRequest.Member> asked = request == null ? List.of()
            : request.members() != null ? request.members()
            : request.userIds() == null ? List.of()
            : request.userIds().stream().map(userId -> new WorkOrderCrewRequest.Member(userId, null)).toList();
        if (asked.size() > MAX_CREW) {
            throw bad("A work order can name at most " + MAX_CREW + " people.");
        }
        Map<String, Integer> wanted = new LinkedHashMap<>();
        for (WorkOrderCrewRequest.Member member : asked) {
            if (member == null || member.userId() == null || member.userId().isBlank()) {
                throw bad("Name each person by their user id.");
            }
            String id = member.userId().trim();
            if (wanted.containsKey(id)) {
                throw bad("Name each person once.");
            }
            int share = member.sharePercent() == null ? FULL_SHARE : member.sharePercent();
            if (share < 1 || share > FULL_SHARE) {
                throw bad("sharePercent must be a whole number from 1 to 100.");
            }
            if (!members.canRead(projectId, id)) {
                throw bad(id + " is not in this project.");
            }
            wanted.put(id, share);
        }
        return wanted;
    }

    /** A crew member's share; rows from before V83 needed all of the person's time. */
    static int shareOf(WorkOrderCrew member) {
        return member.getSharePercent() == null ? FULL_SHARE : member.getSharePercent();
    }

    private WorkOrderCrewResponse response(WorkOrder order) {
        List<WorkOrderCrew> rows = crewRepository.findAllByWorkOrderIdOrderByAddedAtAscCrewIdAsc(order.getWorkOrderId());
        List<String> crew = rows.stream().map(WorkOrderCrew::getUserId).toList();
        OffsetDateTime start = order.getPlannedStartAt();
        OffsetDateTime end = order.getPlannedEndAt();
        boolean window = start != null && end != null && end.isAfter(start)
            && Duration.between(start, end).compareTo(LONGEST_WINDOW) <= 0;
        Map<String, WorkerHours> hours = window && !crew.isEmpty()
            ? workerCalendars.workingHours(order.getProjectId(), crew, start, end) : Map.of();
        List<WorkOrderCrewResponse.Member> list = rows.stream()
            .map(member -> {
                WorkerHours one = hours.get(member.getUserId());
                return one == null ? new WorkOrderCrewResponse.Member(member.getUserId(), shareOf(member), null, null)
                    : new WorkOrderCrewResponse.Member(member.getUserId(), shareOf(member), one.calendarSet(), one.hours());
            })
            .toList();
        return new WorkOrderCrewResponse(order.getWorkOrderId(), start, end, list);
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
