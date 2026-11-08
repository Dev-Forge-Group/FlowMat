package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseSettingRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.PurchaseSettingResponse;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseApprovalStep;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseSetting;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseApprovalStepRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseSettingRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A project's purchasing settings (docs/architecture/adr/ADR-027-purchasing.md decision 14, docs/domain/purchasing.md PO33):
 * the limit from which purchase orders need the project owner's approval, and the members who approve from lower or other values
 * first (PO50). Members read them; only the owner changes them.
 */
@Service
@RequiredArgsConstructor
public class PurchaseSettingService {
    private static final int MAX_INTEGER_DIGITS = 10;
    private static final int MAX_STEPS = 5;

    private final PurchaseSettingRepository settings;
    private final PurchaseApprovalStepRepository steps;
    private final PurchaseOrderRepository orders;
    private final ProjectMemberQuery projectMembers;
    private final ProjectAccess access;
    private final IdGenerator ids;

    @Transactional(readOnly = true)
    public PurchaseSettingResponse get(String projectId) {
        String project = required(projectId);
        access.requireProjectReadAccess(project);
        return response(project, settings.findById(project));
    }

    /** From the version the form was opened at; the same limit and steps again answer as they are (PO33, PO50). */
    @Transactional
    public PurchaseSettingResponse set(PurchaseSettingRequest request) {
        if (request == null) {
            throw bad("Send the purchasing settings.");
        }
        String projectId = required(request.projectId());
        access.requireProjectOwnerAccess(projectId);
        BigDecimal limit = limit(request.approvalLimit());
        List<PurchaseApprovalStep> wanted = request.approvalSteps() == null ? null : steps(projectId, request.approvalSteps());
        orders.lockKey("purchase-setting|" + projectId);
        Optional<PurchaseSetting> current = settings.findById(projectId);
        List<PurchaseApprovalStep> before = current.isEmpty() ? List.of() : steps.findAllByProjectIdOrderByStepNoAsc(projectId);
        if (current.isPresent() && same(current.get().getApprovalLimit(), limit) && (wanted == null || sameSteps(before, wanted))) {
            return response(projectId, current);
        }
        if (!Objects.equals(current.map(PurchaseSetting::getVersion).orElse(null), request.expectedVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The purchasing settings changed since they were opened; reload them and try again.");
        }
        PurchaseSetting saved = current.orElseGet(() -> {
            PurchaseSetting created = new PurchaseSetting();
            created.setProjectId(projectId);
            created.setVersion(-1);
            return created;
        });
        saved.setApprovalLimit(limit);
        saved.setVersion(saved.getVersion() + 1);
        saved.setUpdatedBy(access.requireCurrentUserId());
        saved.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        PurchaseSetting stored = settings.saveAndFlush(saved);
        if (wanted != null) {
            steps.deleteAll(before);
            // The old steps go first, so the new ones can take their numbers and approvers.
            steps.flush();
            steps.saveAllAndFlush(wanted);
        }
        return response(projectId, Optional.of(stored));
    }

    /** The project's approval limit; null when no order needs approval. The caller has checked access. */
    public BigDecimal approvalLimit(String projectId) {
        return settings.findById(projectId).map(PurchaseSetting::getApprovalLimit).orElse(null);
    }

    /**
     * The approvals by order value (PO50), lowest value first: the member steps and, from the approval limit, the project owner,
     * who comes last among equal values. Empty without settings. The caller has checked access.
     */
    List<ApprovalRule> approvalRules(String projectId) {
        Optional<PurchaseSetting> setting = settings.findById(projectId);
        if (setting.isEmpty()) {
            return List.of();
        }
        List<ApprovalRule> rules = new ArrayList<>();
        steps.findAllByProjectIdOrderByStepNoAsc(projectId)
            .forEach(step -> rules.add(new ApprovalRule(step.getFromValue(), step.getApproverId(), false)));
        String owner = projectMembers.ownerOf(projectId);
        if (setting.get().getApprovalLimit() != null && owner != null) {
            rules.add(new ApprovalRule(setting.get().getApprovalLimit(), owner, true));
        }
        rules.sort(Comparator.comparing(ApprovalRule::fromValue).thenComparing(ApprovalRule::projectOwner));
        return List.copyOf(rules);
    }

    /**
     * The member steps as sent (PO50): at most five, each from a value of 0 or more and by a different member who can edit the
     * project, not the owner, who approves from the limit. Numbered from the lowest value.
     */
    private List<PurchaseApprovalStep> steps(String projectId, List<PurchaseSettingRequest.Step> sent) {
        if (sent.size() > MAX_STEPS) {
            throw bad("A project has at most " + MAX_STEPS + " approval steps besides the owner's.");
        }
        String owner = projectMembers.ownerOf(projectId);
        Set<String> seen = new HashSet<>();
        List<PurchaseApprovalStep> result = new ArrayList<>();
        for (PurchaseSettingRequest.Step step : sent) {
            if (step == null) {
                throw bad("approvalSteps must not contain null entries.");
            }
            BigDecimal from = amount(step.fromValue(), "approvalSteps.fromValue");
            if (from == null) {
                throw bad("approvalSteps.fromValue is required.");
            }
            String approver = step.approverId() == null || step.approverId().isBlank() ? null : step.approverId().trim();
            if (approver == null) {
                throw bad("approvalSteps.approverId is required.");
            }
            if (approver.equals(owner)) {
                throw bad("The project owner approves from the approval limit; choose another member for a step.");
            }
            if (!projectMembers.canWrite(projectId, approver)) {
                throw bad("Choose an approver who can edit the project.");
            }
            if (!seen.add(approver)) {
                throw bad(approver + " is on two approval steps.");
            }
            PurchaseApprovalStep row = new PurchaseApprovalStep();
            row.setStepId(ids.generate());
            row.setProjectId(projectId);
            row.setFromValue(from);
            row.setApproverId(approver);
            result.add(row);
        }
        result.sort(Comparator.comparing(PurchaseApprovalStep::getFromValue));
        for (int index = 0; index < result.size(); index++) {
            result.get(index).setStepNo(index + 1);
        }
        return result;
    }

    private static boolean sameSteps(List<PurchaseApprovalStep> before, List<PurchaseApprovalStep> wanted) {
        if (before.size() != wanted.size()) {
            return false;
        }
        for (int index = 0; index < before.size(); index++) {
            if (before.get(index).getFromValue().compareTo(wanted.get(index).getFromValue()) != 0
                || !before.get(index).getApproverId().equals(wanted.get(index).getApproverId())) {
                return false;
            }
        }
        return true;
    }

    private PurchaseSettingResponse response(String projectId, Optional<PurchaseSetting> found) {
        List<PurchaseSettingResponse.Step> approvers = found.isEmpty() ? List.of() : steps.findAllByProjectIdOrderByStepNoAsc(projectId).stream()
            .map(step -> new PurchaseSettingResponse.Step(step.getFromValue(), step.getApproverId(),
                projectMembers.canWrite(projectId, step.getApproverId())))
            .toList();
        return found.map(one -> new PurchaseSettingResponse(projectId, one.getApprovalLimit(), one.getVersion(), one.getUpdatedBy(), one.getUpdatedAt(),
                approvers))
            .orElseGet(() -> new PurchaseSettingResponse(projectId, null, null, null, null, List.of()));
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static BigDecimal limit(BigDecimal value) {
        return amount(value, "approvalLimit");
    }

    /** Empty for none; else 0 or more, at most four decimals and ten whole digits, as order values are kept. */
    private static BigDecimal amount(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw bad(field + " cannot be below 0.");
        }
        if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
            throw bad(field + " has more than four decimals.");
        }
        if (CsvDecimalParser.hasMoreThanIntegerDigits(value, MAX_INTEGER_DIGITS)) {
            throw bad(field + " is too large.");
        }
        return value.setScale(4);
    }

    private static String required(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw bad("projectId is required.");
        }
        return projectId.trim();
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
