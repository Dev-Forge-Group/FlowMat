package org.myweb.flowmat.domain.inventory.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotReleaseHistoryResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.InventoryTransaction;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.entity.LotStatusEvent;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.inventory.repository.LotStatusEventRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A LOT's release history from the stock ledger (docs/domain/lot-release.md R8): each release after the receipt checks and
 * each hold on reopening moves one ledger row per stock record, so rows of one action by one person within a few seconds
 * are one event. Closing and reopening, which move no ledger row on an empty LOT, come from their own events (R9).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LotReleaseHistoryService {
    private static final List<String> TYPES = List.of(InventoryCommandService.INSPECTION_RELEASE, InventoryCommandService.LOT_REOPEN);
    private static final Duration SAME_EVENT = Duration.ofSeconds(5);

    private final LotMasterRepository lots;
    private final InventoryTransactionRepository transactions;
    private final LotStatusEventRepository statusEvents;
    private final ProjectAccessService access;

    public LotReleaseHistoryResponse history(String lotId) {
        LotMaster lot = lots.findById(lotId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "LOT does not exist."));
        access.requireProjectReadAccess(lot.getProjectId());
        List<LotReleaseHistoryResponse.Event> events = new ArrayList<>();
        String lastType = null;
        String lastBy = null;
        OffsetDateTime lastAt = null;
        for (InventoryTransaction row : transactions.findAllByReferenceIdAndReferenceTypeInOrderByCreatedAtDescInventoryTransactionIdDesc(
            lotId, TYPES)) {
            if (!lot.getProjectId().equals(row.getProjectId())) continue;
            boolean same = Objects.equals(lastType, row.getReferenceType()) && Objects.equals(lastBy, row.getCreatedBy())
                && lastAt != null && row.getCreatedAt() != null && Duration.between(row.getCreatedAt(), lastAt).abs().compareTo(SAME_EVENT) <= 0;
            if (same) {
                var last = events.remove(events.size() - 1);
                events.add(new LotReleaseHistoryResponse.Event(last.action(), last.at(), last.by(), last.stockRows() + 1, null));
            } else {
                events.add(new LotReleaseHistoryResponse.Event(
                    InventoryCommandService.INSPECTION_RELEASE.equals(row.getReferenceType()) ? "released" : "held",
                    row.getCreatedAt(), row.getCreatedBy(), 1, null));
            }
            lastType = row.getReferenceType();
            lastBy = row.getCreatedBy();
            lastAt = row.getCreatedAt();
        }
        for (LotStatusEvent event : statusEvents.findAllByLotIdOrderByCreatedAtDescLotStatusEventIdDesc(lotId)) {
            events.add(new LotReleaseHistoryResponse.Event(event.getAction(), event.getCreatedAt(), event.getCreatedBy(), 0,
                event.getToStatus()));
        }
        // Newest first. The sort keeps the order above on the same instant, so a hold stays above the reopening that made it.
        events.sort(Comparator.comparing(LotReleaseHistoryResponse.Event::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return new LotReleaseHistoryResponse(lotId, events);
    }
}
