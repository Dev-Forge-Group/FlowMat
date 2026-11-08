package org.myweb.flowmat.domain.bom.application;

import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionQuery;
import org.myweb.flowmat.domain.bom.application.publicapi.BomRevisionView;
import org.myweb.flowmat.domain.bom.application.publicapi.EffectiveBomView;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BomRevisionQueryImpl implements BomRevisionQuery {
    private final BomHeaderRepository headers;
    private final ProjectAccessService access;
    private final BomRevisionLock revisionLock;
    private final jakarta.persistence.EntityManager entityManager;

    @Override
    @Transactional
    public Optional<EffectiveBomView> findForPlanning(String projectId, String targetItemId, LocalDate on) {
        access.requireProjectReadAccess(projectId);
        revisionLock.lockItem(projectId, targetItemId);
        var revisions = headers.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(projectId, targetItemId, "N");
        // Other owner queries may already have loaded the row before waiting for this item lock.
        revisions.forEach(entityManager::refresh);
        var selected = findEffective(projectId, targetItemId, on);
        if (selected.isEmpty() && revisions.stream().anyMatch(row -> "approved".equals(row.getBomStatus()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "plannedStartAt has no effective approved BOM for targetItemId on " + on + ".");
        }
        return selected;
    }
    @Override
    public Optional<EffectiveBomView> findEffective(String projectId,String targetItemId,LocalDate on) {
        if (projectId==null || projectId.isBlank() || targetItemId==null || targetItemId.isBlank())
            throw new BusinessException(ErrorCode.BAD_REQUEST,"projectId and targetItemId are required.");
        access.requireProjectReadAccess(projectId);
        if(on==null || on.getYear()<1 || on.getYear()>9999)
            throw new BusinessException(ErrorCode.BAD_REQUEST,"on requires a project calendar date with a year from 1 to 9999.");
        var effective=headers.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(projectId,targetItemId,"N")
            .stream().filter(header->"approved".equals(header.getBomStatus()))
            .filter(header->header.getEffectiveFrom()==null || !on.isBefore(header.getEffectiveFrom()))
            .filter(header->header.getEffectiveTo()==null || !on.isAfter(header.getEffectiveTo())).toList();
        if(effective.size()>1)throw new BusinessException(ErrorCode.CONFLICT,"effectiveFrom/effectiveTo overlap for this item on "+on+"; explicitly adjust the approved periods.");
        return effective.stream().findFirst().map(header->new EffectiveBomView(header.getBomId(),header.getTargetItemId(),
            header.getBomVersion(),header.getEffectiveFrom(),header.getEffectiveTo()));
    }

    @Override
    public Optional<BomRevisionView> findActive(String bomId) {
        return headers.findByBomIdAndDeletedYn(bomId, "N").map(header -> new BomRevisionView(header.getBomId(), header.getProjectId(),
            header.getTargetItemId(), header.getBomName(), header.getBomVersion(), header.getBomStatus()));
    }
}
