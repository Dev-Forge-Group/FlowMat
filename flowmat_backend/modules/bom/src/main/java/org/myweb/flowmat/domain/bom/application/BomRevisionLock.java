package org.myweb.flowmat.domain.bom.application;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One lock order for revision numbering, draft edits, approval and explicit period changes. */
@Component
@RequiredArgsConstructor
@Transactional(propagation=Propagation.MANDATORY)
public class BomRevisionLock {
    private final JdbcTemplate jdbc;
    private final EntityManager entities;
    /** Approval serializes the whole item graph so mutually recursive candidates cannot both commit. */
    public void lockProject(String projectId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",Object.class,"bom-approval|"+projectId);
    }
    public void lockItem(String projectId,String itemId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",Object.class,
            "bom-effectivity|"+projectId+"|"+itemId);
    }
    public void lockHeader(BomHeader header) {
        lockItem(header.getProjectId(),header.getTargetItemId());
        entities.refresh(header,LockModeType.PESSIMISTIC_WRITE);
        if(!"N".equals(header.getDeletedYn()))throw new BusinessException(ErrorCode.NOT_FOUND);
    }
}
