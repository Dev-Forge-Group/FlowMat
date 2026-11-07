package org.myweb.flowmat.domain.bom.application;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.bom.api.dto.request.BomEffectivityRequest;
import org.myweb.flowmat.domain.bom.api.dto.response.BomEffectivityResponse;
import org.myweb.flowmat.domain.bom.domain.entity.BomEffectivityChange;
import org.myweb.flowmat.domain.bom.domain.entity.BomHeader;
import org.myweb.flowmat.domain.bom.repository.BomEffectivityChangeRepository;
import org.myweb.flowmat.domain.bom.repository.BomHeaderRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class BomEffectivityService {
    private final BomHeaderRepository headers;
    private final BomEffectivityChangeRepository changes;
    private final ProjectAccessService access;
    private final IdGenerator ids;
    private final BomRevisionLock revisionLock;

    @Transactional(readOnly = true)
    public BomEffectivityResponse get(String bomId) {
        BomHeader header = find(bomId);
        access.requireProjectReadAccess(header.getProjectId());
        return response(header);
    }

    @Transactional
    public BomEffectivityResponse change(String bomId, BomEffectivityRequest request) {
        BomHeader header = find(bomId);
        access.requireProjectWriteAccess(header.getProjectId());
        revisionLock.lockHeader(header);
        if ("approved".equals(header.getBomStatus())) access.requireProjectOwnerAccess(header.getProjectId());
        String actor = access.requireCurrentUserId();
        String reason = request.reason() == null ? "" : request.reason().trim();
        if (reason.isBlank() || reason.length() > 1000 || reason.indexOf('\0') >= 0
            || !StandardCharsets.UTF_8.newEncoder().canEncode(reason)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "reason is required and must be storable text of at most 1000 characters.");
        }
        if (request.requestId() == null || request.expectedPeriodVersion() == null || request.expectedPeriodVersion() < 0)
            throw new BusinessException(ErrorCode.BAD_REQUEST, "requestId and expectedPeriodVersion are required.");
        validateRange(request.effectiveFrom(), request.effectiveTo());
        BomEffectivityChange replay = changes.findByBomIdAndRequestId(bomId, request.requestId()).orElse(null);
        if (replay != null) {
            if (!Objects.equals(replay.getEffectiveFrom(),request.effectiveFrom())
                || !Objects.equals(replay.getEffectiveTo(),request.effectiveTo())
                || replay.getPeriodVersion() != request.expectedPeriodVersion() + 1
                || !reason.equals(replay.getReason()) || !actor.equals(replay.getChangedBy()))
                throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different effective period command.");
            // Return today's state: a replay cannot roll a later explicit change back.
            return response(header);
        }
        if (!"draft".equals(header.getBomStatus()) && !"approved".equals(header.getBomStatus()))
            throw new BusinessException(ErrorCode.CONFLICT, "The effective period can change only on a draft or approved revision.");
        if (!Objects.equals(header.getEffectivePeriodVersion(),request.expectedPeriodVersion()))
            throw new BusinessException(ErrorCode.CONFLICT, "The effective period changed; reload before changing effectiveFrom/effectiveTo.");
        if (Objects.equals(header.getEffectiveFrom(),request.effectiveFrom()) && Objects.equals(header.getEffectiveTo(),request.effectiveTo()))
            throw new BusinessException(ErrorCode.BAD_REQUEST, "effectiveFrom/effectiveTo are unchanged.");
        if ("approved".equals(header.getBomStatus())) requireNoOverlap(header,request.effectiveFrom(),request.effectiveTo());
        BomEffectivityChange change = new BomEffectivityChange();
        change.setChangeId(ids.generate()); change.setBomId(bomId); change.setRequestId(request.requestId());
        change.setPreviousEffectiveFrom(header.getEffectiveFrom()); change.setPreviousEffectiveTo(header.getEffectiveTo());
        change.setEffectiveFrom(request.effectiveFrom()); change.setEffectiveTo(request.effectiveTo());
        change.setPeriodVersion(header.getEffectivePeriodVersion()+1); change.setReason(reason);
        change.setChangedBy(actor); change.setChangedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        header.setEffectiveFrom(request.effectiveFrom()); header.setEffectiveTo(request.effectiveTo());
        header.setEffectivePeriodVersion(change.getPeriodVersion()); header.setUpdatedBy(actor);
        headers.save(header); changes.saveAndFlush(change);
        return response(header);
    }

    /**
     * Ends an approved revision on {@code end} for the revision replacing it, inside that approval (multi-level-bom.md M5):
     * the caller holds the approval locks and owner access. The change is history like any period change.
     */
    void endForReplacement(BomHeader other, LocalDate end, String actor, String reason) {
        revisionLock.lockHeader(other);
        BomEffectivityChange change = new BomEffectivityChange();
        change.setChangeId(ids.generate()); change.setBomId(other.getBomId()); change.setRequestId(java.util.UUID.randomUUID());
        change.setPreviousEffectiveFrom(other.getEffectiveFrom()); change.setPreviousEffectiveTo(other.getEffectiveTo());
        change.setEffectiveFrom(other.getEffectiveFrom()); change.setEffectiveTo(end);
        change.setPeriodVersion(other.getEffectivePeriodVersion() + 1); change.setReason(reason);
        change.setChangedBy(actor); change.setChangedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        other.setEffectiveTo(end); other.setEffectivePeriodVersion(change.getPeriodVersion()); other.setUpdatedBy(actor);
        headers.save(other); changes.saveAndFlush(change);
    }

    private void requireNoOverlap(BomHeader header,LocalDate from,LocalDate to) {
        for (BomHeader other : headers.findAllByProjectIdAndTargetItemIdAndDeletedYnOrderByBomVersionDesc(
            header.getProjectId(),header.getTargetItemId(),"N")) {
            if (!other.getBomId().equals(header.getBomId()) && "approved".equals(other.getBomStatus())
                && overlaps(from,to,other.getEffectiveFrom(),other.getEffectiveTo()))
                throw new BusinessException(ErrorCode.CONFLICT,"effectiveFrom/effectiveTo overlap approved revision v"+other.getBomVersion()+".");
        }
    }
    static boolean overlaps(LocalDate from,LocalDate to,LocalDate otherFrom,LocalDate otherTo) {
        return (to==null || otherFrom==null || !to.isBefore(otherFrom))
            && (otherTo==null || from==null || !otherTo.isBefore(from));
    }
    private static void validateRange(LocalDate from,LocalDate to) {
        if ((from!=null && (from.getYear()<1 || from.getYear()>9999)) || (to!=null && (to.getYear()<1 || to.getYear()>9999)))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"effectiveFrom/effectiveTo require a year from 1 to 9999.");
        if (from!=null && to!=null && to.isBefore(from))
            throw new BusinessException(ErrorCode.BAD_REQUEST,"effectiveTo must not be before effectiveFrom.");
    }
    private BomHeader find(String id) {
        return headers.findByBomIdAndDeletedYn(id,"N").orElseThrow(()->new BusinessException(ErrorCode.NOT_FOUND));
    }
    private BomEffectivityResponse response(BomHeader header) {
        return new BomEffectivityResponse(header.getBomId(),header.getBomStatus(),header.getEffectiveFrom(),header.getEffectiveTo(),
            header.getEffectivePeriodVersion(),changes.findTop50ByBomIdOrderByPeriodVersionDesc(header.getBomId()).stream()
                .map(BomEffectivityResponse.Change::from).toList());
    }
}
