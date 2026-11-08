package org.myweb.flowmat.domain.bom.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomView;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.domain.entity.BomLine;
import org.myweb.flowmat.domain.bom.domain.enums.BomStatus;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.bom.repository.BomLineRepository;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.UnitConverter;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class BomApprovalServiceImpl implements BomApprovalService {

    private static final String NOT_DELETED = BomServiceImpl.NOT_DELETED;

    private final BomHeaderRepository bomHeaderRepository;
    private final BomLineRepository bomLineRepository;
    private final CatalogQuery catalogQuery;
    private final BomRevisionLock revisionLock;
    private final UnitConverter unitConverter;
    private final ProjectAccessService projectAccessService;
    private final BomEffectivityService effectivityService;
    private final ProjectMemberQuery projectMembers;
    private final NotificationCommand notifications;

    @Override
    public BomView submit(String bomId) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectWriteAccess(header.getProjectId());
        List<BomLine> lines = lines(header);
        // Check now so the approver only sees revisions that can pass; approval checks again.
        requireApprovable(header, lines);
        for (BomHeader revision : bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
            header.getProjectId(), header.getTargetItemId(), NOT_DELETED)) {
            if (!revision.getBomId().equals(header.getBomId()) && BomStatus.PENDING_APPROVAL.code().equals(revision.getBomStatus()))
                throw new BusinessException(ErrorCode.CONFLICT, "Revision " + revision.getBomVersion() + " is already pending approval for this item.");
        }
        transition(header, BomStatus.PENDING_APPROVAL);
        String actor = projectAccessService.requireCurrentUserId();
        header.setApprovalStatus("pending");
        header.setSubmittedBy(actor);
        header.setSubmittedAt(OffsetDateTime.now());
        header.setUpdatedBy(actor);
        BomHeader saved = bomHeaderRepository.save(header);
        // The project owner approves, so they are told; an owner submitting a revision themselves is not (NT8).
        notifications.notifyAssignee(projectMembers.ownerOf(saved.getProjectId()), actor, saved.getProjectId(), "bom_approval",
            label(saved) + " waits for your approval", "Submitted by " + actor + ".", "bom", saved.getBomId());
        return BomServiceImpl.toResponse(saved, lines);
    }

    @Override
    public BomView approve(String bomId, String note, boolean endEarlier) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectOwnerAccess(header.getProjectId());
        List<BomLine> lines = lines(header);
        requireApprovable(header, lines);
        if (endEarlier) {
            endEarlierRevisions(header);
        }
        // Approved revisions of an item live side by side for separate periods: approval retires none of them and refuses
        // an overlap (DECISIONS-2026-10-05 section 5, docs/domain/multi-level-bom.md M1-M2).
        requireNoOverlap(header);
        transition(header, BomStatus.APPROVED);
        String actor = projectAccessService.requireCurrentUserId();

        header.setApprovalStatus("approved");
        header.setApprovedBy(actor);
        header.setApprovedAt(OffsetDateTime.now());
        header.setUpdatedBy(actor);
        appendNote(header, note);
        BomHeader saved = bomHeaderRepository.save(header);
        tellSubmitter(saved, actor, "approved", note == null || note.isBlank() ? "Approved." : note.trim());
        return BomServiceImpl.toResponse(saved, lines);
    }

    @Override
    public BomView reject(String bomId, String note) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectOwnerAccess(header.getProjectId());
        if (note == null || note.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Give a reason when rejecting a BOM.");
        }
        transition(header, BomStatus.DRAFT);
        String actor = projectAccessService.requireCurrentUserId();
        header.setApprovalStatus("rejected");
        header.setUpdatedBy(actor);
        appendNote(header, "Rejected: " + note.trim());
        BomHeader saved = bomHeaderRepository.save(header);
        tellSubmitter(saved, actor, "rejected", note.trim());
        return BomServiceImpl.toResponse(saved, lines(saved));
    }

    /** The person who submitted the revision hears the owner's decision (NT8); nobody when it was never submitted. */
    private void tellSubmitter(BomHeader header, String actor, String decision, String body) {
        notifications.notifyAssignee(header.getSubmittedBy(), actor, header.getProjectId(), "bom_decision",
            label(header) + " was " + decision, body, "bom", header.getBomId());
    }

    private static String label(BomHeader header) {
        return "BOM " + (header.getBomName() == null ? header.getTargetItemId() : header.getBomName()) + " v" + header.getBomVersion();
    }

    @Override
    public BomView retire(String bomId, String note) {
        BomHeader header = findBom(bomId);
        projectAccessService.requireProjectOwnerAccess(header.getProjectId());
        transition(header, BomStatus.RETIRED);
        header.setUpdatedBy(projectAccessService.requireCurrentUserId());
        appendNote(header, note);
        return BomServiceImpl.toResponse(bomHeaderRepository.save(header), lines(header));
    }

    /**
     * The replacement helper (docs/domain/multi-level-bom.md M4-M6): every approved revision of the item that overlaps this
     * one must start before this one's start day and must not run past its end; each is ended the day before, with its
     * period history, under the approval locks. Anything else is refused before a change, so a replacement is all or nothing.
     */
    private void endEarlierRevisions(BomHeader header) {
        java.time.LocalDate from = header.getEffectiveFrom();
        if (from == null) {
            throw new BusinessException(ErrorCode.CONFLICT, "Revision " + header.getBomVersion()
                + " has no start day, so there is no day before it to end other revisions on; give it a start in Effective periods.");
        }
        List<BomHeader> replaced = new ArrayList<>();
        for (BomHeader other : bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
            header.getProjectId(), header.getTargetItemId(), NOT_DELETED)) {
            if (other.getBomId().equals(header.getBomId()) || BomStatus.fromCode(other.getBomStatus()) != BomStatus.APPROVED
                || !BomEffectivityService.overlaps(header.getEffectiveFrom(), header.getEffectiveTo(), other.getEffectiveFrom(),
                    other.getEffectiveTo())) {
                continue;
            }
            boolean startsBefore = other.getEffectiveFrom() == null || other.getEffectiveFrom().isBefore(from);
            boolean endsWithin = header.getEffectiveTo() == null
                || (other.getEffectiveTo() != null && !other.getEffectiveTo().isAfter(header.getEffectiveTo()));
            if (!startsBefore || !endsWithin) {
                throw new BusinessException(ErrorCode.CONFLICT, "Approved v" + other.getBomVersion() + " (" + period(other)
                    + ") cannot be ended before " + from + (startsBefore ? ": it runs past this revision's end." : ": it starts on or after that day."));
            }
            replaced.add(other);
        }
        String actor = projectAccessService.requireCurrentUserId();
        for (BomHeader other : replaced) {
            effectivityService.endForReplacement(other, from.minusDays(1), actor,
                "Ended by approving v" + header.getBomVersion() + ", which starts " + from + ".");
        }
    }

    /** Refuses approval while another approved revision of the item covers any of the same days. */
    private void requireNoOverlap(BomHeader header) {
        List<String> overlapping = new ArrayList<>();
        for (BomHeader other : bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
            header.getProjectId(), header.getTargetItemId(), NOT_DELETED)) {
            if (!other.getBomId().equals(header.getBomId()) && BomStatus.fromCode(other.getBomStatus()) == BomStatus.APPROVED
                && BomEffectivityService.overlaps(header.getEffectiveFrom(), header.getEffectiveTo(), other.getEffectiveFrom(),
                    other.getEffectiveTo())) {
                overlapping.add("v" + other.getBomVersion() + " (" + period(other) + ")");
            }
        }
        if (!overlapping.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Revision " + header.getBomVersion() + " (" + period(header)
                + ") overlaps approved " + String.join(", ", overlapping) + ". Approving does not retire other revisions: end"
                + " their effective period or retire them, give this revision a period of its own, then approve again.");
        }
    }

    private static String period(BomHeader header) {
        return (header.getEffectiveFrom() == null ? "open" : header.getEffectiveFrom().toString()) + " → "
            + (header.getEffectiveTo() == null ? "open" : header.getEffectiveTo().toString());
    }

    /** Approval conditions 1–7 of docs/domain/inventory-bom-lot-contract.md §5; reports every violation at once. */
    void requireApprovable(BomHeader header, List<BomLine> lines) {
        List<String> problems = problems(header, lines);
        if (!problems.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "This BOM cannot be approved: " + String.join(" ", problems));
        }
    }

    List<String> problems(BomHeader header, List<BomLine> lines) {
        List<String> problems = new ArrayList<>();
        CatalogItemView target = catalogQuery.findProjectItem(header.getProjectId(), header.getTargetItemId())
            .orElse(null);
        if (target == null) {
            problems.add("The target item no longer exists in this project.");
        } else if (!ItemStatusRule.isActive(target.itemStatus())) {
            problems.add(ItemStatusRule.refusal(target.itemCode(), target.itemStatus(), "approve its BOM"));
        }
        if (header.getBaseQuantity() == null || header.getBaseQuantity().signum() <= 0) {
            problems.add("Base quantity must be greater than 0.");
        } else if (target != null) {
            convertible(header.getBaseQuantity(), header.getBaseUnit(), target, "Base quantity", problems);
        }
        if (lines.isEmpty()) {
            problems.add("Add at least one material.");
        }

        Map<String, CatalogItemView> items = catalogQuery.findProjectItems(header.getProjectId()).stream()
            .collect(Collectors.toMap(CatalogItemView::itemId, Function.identity()));
        Set<String> seen = new HashSet<>();
        for (BomLine line : lines) {
            CatalogItemView child = items.get(line.getChildItemId());
            String label = child != null ? child.itemCode() : line.getChildItemId();
            if (child == null || !header.getProjectId().equals(child.projectId())) {
                problems.add("Material " + label + " no longer exists in this project.");
                continue;
            }
            if (line.getQuantity() == null || line.getQuantity().signum() <= 0) {
                problems.add("Material " + label + " needs a quantity greater than 0.");
            }
            if (!ItemStatusRule.isActive(child.itemStatus())) {
                problems.add("Material " + ItemStatusRule.refusal(child.itemCode(), child.itemStatus(), "use it in a BOM"));
            }
            if (child.itemId().equals(header.getTargetItemId())) {
                problems.add("Material " + label + " is the item this BOM produces.");
            }
            if (!seen.add(child.itemId())) {
                problems.add("Material " + label + " appears more than once; combine the lines.");
            }
            if (line.getSubstituteGroup() != null && !BomTree.isMaterial(line.getLineType())) {
                problems.add("Material " + label + ": only a material line can be in a substitute group.");
            } else if (line.getSubstituteGroup() != null && "Y".equals(line.getPhantomYn())) {
                problems.add("Material " + label + ": a phantom cannot be in a substitute group.");
            }
            // An optional material is a plain material a work order may pick (ADR-033 decision 1).
            if ("Y".equals(line.getOptionalYn()) && !BomTree.isMaterial(line.getLineType())) {
                problems.add("Material " + label + ": only a material line can be optional.");
            } else if ("Y".equals(line.getOptionalYn()) && line.getSubstituteGroup() != null) {
                problems.add("Material " + label + ": an optional material cannot be in a substitute group.");
            }
            String scrapProblem = BomTree.scrapRateProblem(line.getScrapRate(), line.getLineType());
            if (scrapProblem != null) {
                problems.add("Material " + label + ": " + scrapProblem + ".");
            }
            if (line.getQuantity() != null && line.getQuantity().signum() > 0) {
                convertible(line.getQuantity(), line.getUnit(), child, "Material " + label, problems);
            }
            if ("Y".equals(line.getPhantomYn())) {
                // A phantom is used through its own BOM, so it needs one (docs/domain/multi-level-bom.md P1).
                if (!BomTree.isMaterial(line.getLineType())) {
                    problems.add("Material " + label + ": only a material line can be a phantom.");
                } else if (bomHeaderRepository.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
                        header.getProjectId(), child.itemId(), NOT_DELETED).stream()
                    .noneMatch(other -> BomStatus.fromCode(other.getBomStatus()) == BomStatus.APPROVED)) {
                    problems.add("Material " + label + " is a phantom but has no approved BOM of its own.");
                }
            }
        }

        // A substitute group has two lines or more, each with a priority of its own (ADR-032 decision 4).
        Map<String, List<BomLine>> groups = lines.stream().filter(line -> line.getSubstituteGroup() != null)
            .collect(Collectors.groupingBy(BomLine::getSubstituteGroup, java.util.LinkedHashMap::new, Collectors.toList()));
        Function<BomLine, String> code = line -> items.containsKey(line.getChildItemId())
            ? items.get(line.getChildItemId()).itemCode() : line.getChildItemId();
        groups.values().forEach(group -> {
            List<BomLine> ordered = group.stream().sorted(BomTree.PREFERENCE).toList();
            String main = code.apply(ordered.get(0));
            if (ordered.size() < 2) {
                problems.add("Material " + main + " is alone in its substitute group; add a substitute or take it out of the group.");
            }
            Set<Integer> priorities = new HashSet<>();
            ordered.stream().filter(line -> !priorities.add(line.getPriority() == null ? 0 : line.getPriority()))
                .forEach(line -> problems.add("Substitutes of " + main + ": " + code.apply(line)
                    + " has the same priority as another material of the group; give each its own."));
        });

        // Multi-level (docs/domain/multi-level-bom.md): a material may have its own approved BOM, but no BOM may contain its
        // own item through its materials' BOMs, and the tree may be at most MAX_LEVELS deep. Every approved revision counts,
        // whatever its period; the same item's revisions are left out, as this one is checked on its own.
        Map<String, List<String>> tree = BomTree.approvedChildren(bomHeaderRepository, bomLineRepository, header.getProjectId(),
            header.getTargetItemId());
        List<BomLine> materials = lines.stream().filter(line -> BomTree.isMaterial(line.getLineType())).toList();
        for (BomLine line : materials) {
            List<String> loop = BomTree.pathTo(line.getChildItemId(), header.getTargetItemId(), tree);
            if (loop != null) {
                Map<String, String> codes = codes(loop);
                problems.add("Material " + codes.get(line.getChildItemId()) + " is made from "
                    + codes.get(header.getTargetItemId()) + " through its own BOM ("
                    + loop.stream().map(codes::get).collect(Collectors.joining(" → ")) + "); a BOM cannot contain itself.");
            }
        }
        int depth = 1 + materials.stream().mapToInt(line -> BomTree.depth(line.getChildItemId(), tree)).max().orElse(0);
        if (depth > BomTree.MAX_LEVELS) {
            problems.add("With its materials' own BOMs this BOM would be " + depth + " levels deep; at most "
                + BomTree.MAX_LEVELS + " are allowed.");
        }
        return problems;
    }

    private Map<String, String> codes(List<String> itemIds) {
        Map<String, String> codes = new java.util.HashMap<>();
        catalogQuery.findItems(itemIds).values().forEach(item -> codes.put(item.itemId(), item.itemCode()));
        itemIds.forEach(id -> codes.putIfAbsent(id, id));
        return codes;
    }

    private void convertible(BigDecimal quantity, String unit, CatalogItemView item, String label, List<String> problems) {
        try {
            unitConverter.toItemUnit(quantity, unit, item.unitId());
        } catch (BusinessException e) {
            problems.add(label + ": " + e.getMessage());
        }
    }

    private static void transition(BomHeader header, BomStatus next) {
        BomStatus current = BomStatus.fromCode(header.getBomStatus());
        if (!current.canTransitionTo(next)) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Revision " + header.getBomVersion() + " is " + current.code() + " and cannot become " + next.code() + ".");
        }
        header.setBomStatus(next.code());
    }

    private static void appendNote(BomHeader header, String note) {
        if (note == null || note.isBlank()) {
            return;
        }
        header.setNote(header.getNote() == null ? note.trim() : header.getNote() + "\n" + note.trim());
    }

    private BomHeader findBom(String bomId) {
        BomHeader header = bomHeaderRepository.findByBomIdAndDeletedYn(bomId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        projectAccessService.requireProjectReadAccess(header.getProjectId());
        revisionLock.lockProject(header.getProjectId());
        revisionLock.lockHeader(header);
        return header;
    }

    private List<BomLine> lines(BomHeader header) {
        return bomLineRepository.findAllByBomIdOrderBySortOrderAscBomLineIdAsc(header.getBomId());
    }
}
