package org.myweb.flowmat.domain.workflow.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.workflow.api.dto.request.ProcessRequirementsRequest;
import org.myweb.flowmat.domain.workflow.api.dto.response.ProcessRequirementResponse;
import org.myweb.flowmat.domain.workflow.domain.contract.WorkflowText;
import org.myweb.flowmat.domain.workflow.domain.entity.Process;
import org.myweb.flowmat.domain.workflow.domain.entity.ProcessRequirement;
import org.myweb.flowmat.domain.workflow.domain.entity.Workflow;
import org.myweb.flowmat.domain.workflow.repository.ProcessRequirementRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A node's execution requirements (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 4): saved on the
 * draft node, fixed into a revision when it is published, and read by plans from there. The whole list is replaced under
 * the node's row lock.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProcessRequirementService {

    private static final Set<String> KINDS = Set.of("equipment", "crew", "skill", "compute", "time");
    private static final Set<String> PERS = Set.of("batch", "output_unit");
    private static final Set<String> TIME_UNITS = Set.of("s", "min", "h");
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("9999999999.9999");

    private final WorkflowAccessService workflowAccess;
    private final ProcessRequirementRepository requirements;
    private final CatalogQuery catalogQuery;
    private final IdGenerator idGenerator;
    private final EntityManager entities;

    public List<ProcessRequirementResponse> list(String processId) {
        Process process = workflowAccess.requireProcessReadAccess(processId);
        workflowAccess.requireWorkflowReadAccess(process.getWorkflowId());
        return requirements.findAllByProcessIdOrderBySortOrderAsc(processId).stream().map(ProcessRequirementResponse::from).toList();
    }

    @Transactional
    public List<ProcessRequirementResponse> replace(String processId, ProcessRequirementsRequest request) {
        Process process = workflowAccess.requireProcessWriteAccess(processId);
        entities.refresh(process, LockModeType.PESSIMISTIC_WRITE);
        if (!"N".equals(process.getDeletedYn())) throw new BusinessException(ErrorCode.NOT_FOUND);
        Workflow workflow = workflowAccess.requireWorkflowWriteAccess(process.getWorkflowId());
        List<ProcessRequirement> saved = new ArrayList<>();
        int order = 0;
        for (ProcessRequirementsRequest.Requirement given : request.requirements()) {
            saved.add(requirement(processId, order++, given, workflow.getProjectId()));
        }
        requirements.deleteAllOfProcess(processId);
        return requirements.saveAll(saved).stream().map(ProcessRequirementResponse::from).toList();
    }

    private ProcessRequirement requirement(String processId, int order, ProcessRequirementsRequest.Requirement given,
        String projectId) {
        String kind = given.kind().trim().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "kind is equipment, crew, skill, compute or time.");
        }
        String per = given.per() == null || given.per().isBlank() ? "batch" : given.per().trim().toLowerCase(Locale.ROOT);
        if (!PERS.contains(per)) throw new BusinessException(ErrorCode.BAD_REQUEST, "per is batch or output_unit.");
        BigDecimal amount = given.amount();
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0 || amount.stripTrailingZeros().scale() > 4) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "amount must be more than 0, at most 9999999999.9999, with 4 decimals.");
        }
        WorkflowText.requireStorable(given.ref(), "ref");
        WorkflowText.requireStorable(given.unit(), "unit");
        String ref = given.ref() == null || given.ref().isBlank() ? null : given.ref().trim();
        String unit = given.unit() == null || given.unit().isBlank() ? null : given.unit().trim();
        if (kind.equals("time")) {
            unit = unit == null ? "min" : unit.toLowerCase(Locale.ROOT);
            if (!TIME_UNITS.contains(unit)) throw new BusinessException(ErrorCode.BAD_REQUEST, "A time's unit is s, min or h.");
        }
        if (kind.equals("equipment") && ref != null && catalogQuery.findProjectEquipment(projectId, ref).isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Equipment " + ref + " is not in this project.");
        }
        ProcessRequirement requirement = new ProcessRequirement();
        requirement.setProcessRequirementId(idGenerator.generate());
        requirement.setProcessId(processId);
        requirement.setSortOrder(order);
        requirement.setKind(kind);
        requirement.setRef(ref);
        requirement.setAmount(amount);
        requirement.setUnit(unit);
        requirement.setPer(per);
        return requirement;
    }
}
