package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanAssignRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountPlanRecordRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryCountRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryCountPlanResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlan;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryCountPlanLine;
import org.myweb.flowmat.domain.inventory.repository.InventoryCountPlanRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryCountPlanLineRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryCountPlanService {
    private final InventoryCountPlanRepository plans;
    private final InventoryCountPlanLineRepository entries;
    private final InventoryRepository stocks;
    private final InventoryCountService countService;
    private final ProjectAccess access;
    private final ProjectMemberQuery projectMembers;
    private final NotificationCommand notifications;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public List<InventoryCountPlanResponse> list(String projectId) {
        access.requireProjectReadAccess(projectId);
        boolean owner = owner(projectId);
        List<InventoryCountPlan> found = plans.findTop50ByProjectIdOrderByCreatedAtDesc(projectId);
        if (found.isEmpty()) return List.of();
        Map<String, List<InventoryCountPlanLine>> rows = entries.findAllByPlanIdInOrderByInventoryIdAsc(
            found.stream().map(InventoryCountPlan::getPlanId).toList()).stream().collect(Collectors.groupingBy(InventoryCountPlanLine::getPlanId));
        return found.stream().map(plan -> view(plan, rows.getOrDefault(plan.getPlanId(), List.of()), owner)).toList();
    }

    @Transactional(readOnly = true)
    public InventoryCountPlanResponse get(String planId) {
        InventoryCountPlan plan = plans.findById(planId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(plan.getProjectId());
        return view(plan);
    }

    @Transactional
    public InventoryCountPlanResponse create(InventoryCountPlanCreateRequest request) {
        String project = required(request.projectId(), "projectId");
        access.requireProjectWriteAccess(project);
        String actor = access.requireCurrentUserId();
        String note = text(request.note(), "note");
        List<String> inventoryIds = request.inventoryIds().stream().map(id -> required(id, "inventoryId")).sorted().toList();
        if (new HashSet<>(inventoryIds).size() != inventoryIds.size()) throw bad("An inventoryId appears twice in this plan.");
        plans.lockRequestKey("count-plan|" + project + "|" + request.requestId());
        InventoryCountPlan existing = plans.findByProjectIdAndRequestId(project, request.requestId()).orElse(null);
        if (existing != null) return createReplay(existing, inventoryIds, request.blind(), note, actor);
        Map<String, Inventory> locked = lockStocks(project, inventoryIds);
        InventoryCountPlan plan = new InventoryCountPlan(); plan.setPlanId(ids.generate()); plan.setProjectId(project);
        plan.setRequestId(request.requestId()); plan.setBlind(request.blind()); plan.setStatus("open"); plan.setNote(note);
        plan.setCreatedBy(actor); plan.setCreatedAt(OffsetDateTime.now()); plans.save(plan);
        List<InventoryCountPlanLine> lines = new ArrayList<>();
        for (Inventory stock : locked.values()) {
            InventoryCountPlanLine line = new InventoryCountPlanLine(); line.setLineId(ids.generate()); line.setPlanId(plan.getPlanId());
            line.setInventoryId(stock.getInventoryId()); line.setItemId(stock.getItemId()); line.setLotId(stock.getLotId());
            line.setLocation(stock.getLocation()); line.setBaselineQuantity(zero(stock.getQuantity()));
            line.setBaselineVersion(stock.getVersion()); line.setCheckpointQuantity(line.getBaselineQuantity());
            line.setCheckpointVersion(line.getBaselineVersion()); lines.add(line);
        }
        entries.saveAllAndFlush(lines);
        return view(plan, lines, owner(project));
    }

    @Transactional
    public InventoryCountPlanResponse record(String planId, String lineId, InventoryCountPlanRecordRequest request) {
        InventoryCountPlan plan = writable(planId);
        List<InventoryCountPlanLine> lines = entries.findAllByPlanIdOrderByInventoryIdAsc(planId);
        InventoryCountPlanLine line = line(lines, lineId);
        if (line.isRequiresRecount()) throw conflict("Start a recount of this row before recording its new measurement.");
        BigDecimal quantity = request.countedQuantity();
        if (quantity == null || quantity.signum() < 0 || quantity.compareTo(new BigDecimal("9999999999.99995")) >= 0) {
            throw bad("countedQuantity must round to a value from 0 to 9999999999.9999.");
        }
        // Below half a stored unit, avoid constructing an enormous power of ten just to round to zero.
        quantity = quantity.compareTo(new BigDecimal("0.00005")) < 0
            ? BigDecimal.ZERO.setScale(4) : quantity.setScale(4, RoundingMode.HALF_UP);
        String actor = access.requireCurrentUserId();
        if (!Objects.equals(line.getEntryVersion(), request.expectedEntryVersion())) {
            if (actor.equals(line.getCountedBy()) && line.getCountedQuantity() != null && quantity.compareTo(line.getCountedQuantity()) == 0) {
                return view(plan, lines, owner(plan.getProjectId()));
            }
            throw conflict("This measurement was changed by another request. Reload it before recording again.");
        }
        line.setCountedQuantity(quantity); line.setCountedBy(actor); line.setCountedAt(OffsetDateTime.now());
        entries.saveAndFlush(line);
        return view(plan, lines, owner(plan.getProjectId()));
    }

    @Transactional
    public InventoryCountPlanResponse recount(String planId, String lineId) {
        InventoryCountPlan plan = writable(planId);
        List<InventoryCountPlanLine> lines = entries.findAllByPlanIdOrderByInventoryIdAsc(planId);
        InventoryCountPlanLine line = line(lines, lineId);
        if (!line.isRequiresRecount()) throw conflict("Only a changed row needs a new recount checkpoint.");
        Inventory stock = lockStocks(plan.getProjectId(), List.of(line.getInventoryId())).get(line.getInventoryId());
        line.setCheckpointQuantity(zero(stock.getQuantity())); line.setCheckpointVersion(stock.getVersion());
        line.setCountedQuantity(null); line.setCountedBy(null); line.setCountedAt(null); line.setRequiresRecount(false);
        entries.saveAndFlush(line);
        plan.setStatus(lines.stream().anyMatch(InventoryCountPlanLine::isRequiresRecount) ? "recount_required" : "open");
        return view(plans.saveAndFlush(plan), lines, owner(plan.getProjectId()));
    }

    /**
     * Gives an open plan to the project owner or an active member, who hears of it unless they took it themselves; blank gives
     * it to no one (docs/domain/stock-count.md P9-P10). Giving it to the same person again tells no one.
     */
    @Transactional
    public InventoryCountPlanResponse assign(String planId, InventoryCountPlanAssignRequest request) {
        InventoryCountPlan plan = writable(planId);
        String userId = request == null ? null : text(request.assignedTo(), "assignedTo");
        if (userId != null && !userId.equals(projectMembers.ownerOf(plan.getProjectId())) && !projectMembers.isActiveMember(plan.getProjectId(), userId)) {
            throw bad(userId + " is not a member of this project.");
        }
        String before = plan.getAssignedTo();
        plan.setAssignedTo(userId);
        List<InventoryCountPlanLine> lines = entries.findAllByPlanIdOrderByInventoryIdAsc(planId);
        InventoryCountPlan saved = plans.saveAndFlush(plan);
        if (userId != null && !userId.equals(before)) {
            notifications.notifyAssignee(userId, access.requireCurrentUserId(), plan.getProjectId(), "count_plan",
                "Count plan " + (plan.getNote() == null ? "of " + lines.size() + (lines.size() == 1 ? " record" : " records") : plan.getNote()) + " is yours",
                (plan.isBlind() ? "A blind count of " : "A count of ") + lines.size() + (lines.size() == 1 ? " stock record." : " stock records."),
                "count_plan", plan.getPlanId());
        }
        return view(saved, lines, owner(plan.getProjectId()));
    }

    @Transactional
    public InventoryCountPlanResponse submit(String planId) {
        InventoryCountPlan plan = plans.findForUpdate(planId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(plan.getProjectId());
        if ("submitted".equals(plan.getStatus())) return view(plan);
        List<InventoryCountPlanLine> lines = entries.findAllByPlanIdOrderByInventoryIdAsc(planId);
        if (lines.stream().anyMatch(InventoryCountPlanLine::isRequiresRecount)) return view(plan);
        if (lines.stream().anyMatch(line -> line.getCountedQuantity() == null)) throw bad("Record every row before submitting the plan.");
        Map<String, Inventory> locked = lockStocks(plan.getProjectId(), lines.stream().map(InventoryCountPlanLine::getInventoryId).toList());
        boolean changed = false;
        for (InventoryCountPlanLine line : lines) {
            Inventory stock = locked.get(line.getInventoryId());
            if (!Objects.equals(line.getCheckpointVersion(), stock.getVersion()) || zero(stock.getQuantity()).compareTo(line.getCheckpointQuantity()) != 0) {
                line.setRequiresRecount(true); changed = true;
            }
        }
        if (changed) {
            // Commit these markers first; the controller returns 409 only after this transaction has committed.
            plan.setStatus("recount_required"); entries.saveAllAndFlush(lines);
            return view(plans.saveAndFlush(plan), lines, owner(plan.getProjectId()));
        }
        for (InventoryCountPlanLine line : lines) if (line.getCountedQuantity().compareTo(zero(locked.get(line.getInventoryId()).getReservedQuantity())) < 0) {
            throw conflict("A counted row is below its reserved stock. Release its reservation before submitting.");
        }
        var counted = countService.count(new InventoryCountRequest(plan.getProjectId(), "plan:" + planId, plan.getNote(),
            lines.stream().map(line -> new InventoryCountRequest.Line(line.getInventoryId(), line.getCountedQuantity(), line.getCheckpointQuantity())).toList()));
        // The underlying count clears its persistence context; explicitly merge the plan after recording movements.
        plan.setStatus("submitted"); plan.setCountId(counted.countId()); plan.setSubmittedBy(access.requireCurrentUserId());
        plan.setSubmittedAt(OffsetDateTime.now());
        return view(plans.saveAndFlush(plan), lines, owner(plan.getProjectId()));
    }

    private InventoryCountPlan writable(String id) {
        InventoryCountPlan plan = plans.findForUpdate(id).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(plan.getProjectId());
        if ("submitted".equals(plan.getStatus())) throw conflict("A submitted plan can no longer be changed.");
        return plan;
    }
    private Map<String, Inventory> lockStocks(String project, List<String> inventoryIds) {
        Map<String, Inventory> locked = new LinkedHashMap<>();
        for (String id : inventoryIds.stream().sorted().toList()) {
            Inventory stock = stocks.findForUpdate(id).filter(row -> "N".equals(row.getDeletedYn()) && project.equals(row.getProjectId()))
                .orElseThrow(() -> bad("An inventoryId was not found in this project."));
            locked.put(id, stock);
        }
        return locked;
    }
    private InventoryCountPlanResponse createReplay(InventoryCountPlan plan, List<String> inventoryIds, boolean blind, String note, String actor) {
        List<InventoryCountPlanLine> lines = entries.findAllByPlanIdOrderByInventoryIdAsc(plan.getPlanId());
        if (plan.isBlind() != blind || !Objects.equals(note, plan.getNote()) || !actor.equals(plan.getCreatedBy())
            || !inventoryIds.equals(lines.stream().map(InventoryCountPlanLine::getInventoryId).sorted().toList())) {
            throw conflict("requestId belongs to a different count plan.");
        }
        return view(plan, lines, owner(plan.getProjectId()));
    }
    private static InventoryCountPlanLine line(List<InventoryCountPlanLine> lines, String id) {
        return lines.stream().filter(line -> line.getLineId().equals(id)).findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }
    private boolean owner(String project) { return "owner".equals(access.resolveCurrentUserRole(project)); }
    private InventoryCountPlanResponse view(InventoryCountPlan plan) {
        return view(plan, entries.findAllByPlanIdOrderByInventoryIdAsc(plan.getPlanId()), owner(plan.getProjectId()));
    }
    private static InventoryCountPlanResponse view(InventoryCountPlan plan, List<InventoryCountPlanLine> lines, boolean owner) {
        return new InventoryCountPlanResponse(plan.getPlanId(), plan.getProjectId(), plan.isBlind(), plan.getStatus(), plan.getNote(),
            plan.getCreatedBy(), plan.getCreatedAt(), plan.getSubmittedBy(), plan.getSubmittedAt(), plan.getCountId(),
            lines.stream().map(line -> new InventoryCountPlanResponse.Line(line.getLineId(), line.getInventoryId(), line.getItemId(), line.getLotId(),
                line.getLocation(), owner ? line.getBaselineQuantity() : null, owner ? line.getCheckpointQuantity() : null,
                line.getCountedQuantity(), line.isRequiresRecount(), line.getCountedBy(), line.getCountedAt(), line.getEntryVersion())).toList(),
            plan.getAssignedTo());
    }
    private static String required(String value, String field) {
        String normalized = text(value, field);
        if (normalized == null) throw bad(field + " is required.");
        return normalized;
    }
    private static String text(String value, String field) {
        if (value == null) return null;
        if (value.indexOf(0) >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw bad(field + " contains an unstorable character.");
        String trimmed = value.trim(); return trimmed.isBlank() ? null : trimmed;
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST, message); }
    private static BusinessException conflict(String message) { return new BusinessException(ErrorCode.CONFLICT, message); }
}
