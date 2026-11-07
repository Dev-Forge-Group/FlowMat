package org.myweb.flowmat.domain.quality.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotReleaseCommand;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.quality.api.dto.response.LotReleaseResponse;
import org.myweb.flowmat.domain.quality.domain.entity.InspectionStandard;
import org.myweb.flowmat.domain.quality.domain.entity.QualityInspection;
import org.myweb.flowmat.domain.quality.repository.InspectionStandardRepository;
import org.myweb.flowmat.domain.quality.repository.QualityInspectionRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases a LOT that waits for its receipt checks (docs/domain/lot-release.md R3): every active required standard of the
 * LOT's item for receipt (`receipt` or `any`) must have a passing latest result for this LOT, the same match the LOT's
 * Receipt checks list shows. Then inventory releases the LOT's rows in the same transaction.
 */
@Service
@RequiredArgsConstructor
public class LotReleaseService {

    private static final String NOT_DELETED = "N";
    private static final String YES = "Y";

    private final LotQuery lotQuery;
    private final LotReleaseCommand lotReleaseCommand;
    private final InspectionStandardRepository standardRepository;
    private final QualityInspectionRepository inspectionRepository;
    private final ProjectAccessService projectAccessService;

    @Transactional
    public LotReleaseResponse release(String lotId) {
        LotView lot = lotQuery.findLots(List.of(lotId)).get(lotId);
        if (lot == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "LOT does not exist.");
        }
        projectAccessService.requireProjectWriteAccess(lot.projectId());

        List<QualityInspection> newestFirst = new ArrayList<>(inspectionRepository.findAllByLotIdOrderByInspectedAtDesc(lot.lotId()));
        newestFirst.sort(Comparator.comparing(QualityInspection::getInspectedAt, Comparator.nullsLast(Comparator.reverseOrder())));
        List<String> missing = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<InspectionStandard> required = standardRepository.findAllByItemIdAndDeletedYn(lot.itemId(), NOT_DELETED).stream()
            .filter(standard -> YES.equals(standard.getActiveYn()) && YES.equals(standard.getRequiredYn())
                && !"production".equals(standard.getStage()))
            .sorted(Comparator.comparing(standard -> standard.getInspectionType().toLowerCase()))
            .toList();
        required.forEach(standard -> {
                QualityInspection latest = newestFirst.stream().filter(one -> follows(one, standard)).findFirst().orElse(null);
                if (latest == null) {
                    missing.add(standard.getInspectionType());
                } else if (!QualityInspection.PASS.equals(latest.getResultStatus())) {
                    failed.add(standard.getInspectionType());
                }
            });
        if (!missing.isEmpty() || !failed.isEmpty()) {
            List<String> reasons = new ArrayList<>();
            if (!missing.isEmpty()) {
                reasons.add("record " + String.join(", ", missing));
            }
            if (!failed.isEmpty()) {
                reasons.add(String.join(", ", failed) + " failed");
            }
            throw new BusinessException(ErrorCode.CONFLICT,
                "Before releasing LOT " + lot.lotNo() + ": " + String.join("; ", reasons) + ".");
        }
        String status = lotReleaseCommand.releaseAfterReceiptChecks(lot.lotId(), projectAccessService.requireCurrentUserId());
        return new LotReleaseResponse(lot.lotId(), lot.lotNo(), status, required.size());
    }

    /** The inspection followed the standard, or names no standard but is the same check of the same item. */
    private static boolean follows(QualityInspection inspection, InspectionStandard standard) {
        if (inspection.getStandardId() != null) {
            return inspection.getStandardId().equals(standard.getStandardId());
        }
        return standard.getItemId().equals(inspection.getItemId())
            && standard.getInspectionType().equalsIgnoreCase(inspection.getInspectionType());
    }
}
