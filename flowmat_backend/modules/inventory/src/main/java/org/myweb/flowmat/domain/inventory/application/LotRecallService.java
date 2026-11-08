package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.request.LotRecallQuarantineRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotRecallQuarantineResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotRecallResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.LotTraceResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.enums.InventoryTransactionType;
import org.myweb.flowmat.domain.inventory.domain.enums.LotStatus;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository.LotIssued;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LOT recall (docs/domain/lot-recall.md): from a suspect LOT, every LOT made from it (the forward genealogy), where
 * their stock is and what already left. Holding them all goes through the ordinary LOT quarantine, one movement per
 * LOT pointing back at the suspect LOT.
 */
@Service
@RequiredArgsConstructor
public class LotRecallService {

    public static final String REFERENCE_TYPE = "lot_recall";
    private static final String NOT_DELETED = "N";
    private static final int SCALE = 4;

    private final LotService lotService;
    private final InventoryRepository inventoryRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final CatalogQuery catalogQuery;
    private final InventoryCommandService inventoryCommandService;
    private final ProjectAccess projectAccessService;

    /** The suspect LOT and what it went into; read access is checked by the trace. */
    @Transactional(readOnly = true)
    public LotRecallResponse recall(String lotId) {
        LotTraceResponse trace = lotService.trace(lotId, "forward");
        // The start first, then the genealogy in trace order (by depth).
        Map<String, LotResponse> lots = new LinkedHashMap<>();
        Map<String, Integer> depth = new HashMap<>();
        Map<String, String> via = new HashMap<>();
        lots.put(trace.lot().lotId(), trace.lot());
        depth.put(trace.lot().lotId(), 0);
        for (LotTraceResponse.Node node : trace.nodes()) {
            lots.putIfAbsent(node.lot().lotId(), node.lot());
            depth.putIfAbsent(node.lot().lotId(), node.depth());
            via.putIfAbsent(node.lot().lotId(), node.viaLotId());
        }

        Map<String, List<Inventory>> records = inventoryRepository.findAllByLotIdInAndDeletedYn(lots.keySet(), NOT_DELETED).stream()
            .collect(Collectors.groupingBy(Inventory::getLotId));
        Map<String, BigDecimal> issued = transactionRepository.findIssuedByLot(lots.keySet()).stream()
            .collect(Collectors.toMap(LotIssued::getLotId, LotIssued::getIssued));
        Map<String, CatalogItemView> items = catalogQuery.findItems(
            lots.values().stream().map(LotResponse::itemId).collect(Collectors.toSet()));

        List<LotRecallResponse.Line> lines = new ArrayList<>();
        for (LotResponse lot : lots.values()) {
            List<Inventory> held = records.getOrDefault(lot.lotId(), List.of());
            BigDecimal onHand = held.stream().map(Inventory::getQuantity).filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<String> places = held.stream()
                .filter(row -> row.getQuantity() != null && row.getQuantity().signum() > 0)
                .map(row -> (row.getLocation() == null ? "no location" : row.getLocation()) + " "
                    + row.getQuantity().stripTrailingZeros().toPlainString())
                .toList();
            CatalogItemView item = items.get(lot.itemId());
            LotResponse viaLot = via.get(lot.lotId()) == null ? null : lots.get(via.get(lot.lotId()));
            lines.add(new LotRecallResponse.Line(
                lot.lotId(),
                lot.lotNo(),
                lot.itemId(),
                item == null ? null : item.itemCode(),
                item == null ? null : item.itemName(),
                item == null ? null : item.unitCode(),
                depth.get(lot.lotId()),
                viaLot == null ? null : viaLot.lotNo(),
                lot.lotStatus(),
                scale(onHand),
                places,
                scale(issued.getOrDefault(lot.lotId(), BigDecimal.ZERO))
            ));
        }
        return new LotRecallResponse(trace.lot().lotId(), trace.lot().lotNo(), lines);
    }

    /**
     * Quarantines the suspect LOT and everything made from it, in one transaction. LOTs already quarantined, closed,
     * or without a stock record are left as they are and listed with the reason.
     */
    @Transactional
    public LotRecallQuarantineResponse quarantine(String lotId, LotRecallQuarantineRequest request) {
        LotResponse start = lotService.getLot(lotId);
        projectAccessService.requireProjectWriteAccess(start.projectId());
        String actor = projectAccessService.requireCurrentUserId();
        LotRecallResponse recall = recall(lotId);
        Map<String, List<Inventory>> records = inventoryRepository.findAllByLotIdInAndDeletedYn(
                recall.lots().stream().map(LotRecallResponse.Line::lotId).toList(), NOT_DELETED).stream()
            .collect(Collectors.groupingBy(Inventory::getLotId));
        String note = "Recall of LOT " + recall.lotNo() + ": " + request.reason().trim();
        List<String> quarantined = new ArrayList<>();
        List<LotRecallQuarantineResponse.Skipped> skipped = new ArrayList<>();
        for (LotRecallResponse.Line lot : recall.lots()) {
            if (LotStatus.QUARANTINED.code().equals(lot.lotStatus())) {
                skipped.add(new LotRecallQuarantineResponse.Skipped(lot.lotNo(), "already quarantined"));
                continue;
            }
            if (LotStatus.CLOSED.code().equals(lot.lotStatus())) {
                skipped.add(new LotRecallQuarantineResponse.Skipped(lot.lotNo(), "closed"));
                continue;
            }
            List<Inventory> held = records.getOrDefault(lot.lotId(), List.of());
            if (held.isEmpty()) {
                skipped.add(new LotRecallQuarantineResponse.Skipped(lot.lotNo(), "no stock record"));
                continue;
            }
            // One movement quarantines every record of the LOT (docs/domain/inventory-bom-lot-contract.md §6).
            inventoryCommandService.apply(new InventoryMovement(held.get(0).getInventoryId(), InventoryTransactionType.QUARANTINE,
                BigDecimal.ZERO, BigDecimal.ZERO, REFERENCE_TYPE, recall.lotId(), note, null, actor));
            quarantined.add(lot.lotNo());
        }
        return new LotRecallQuarantineResponse(quarantined, skipped);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
