package org.myweb.flowmat.domain.inventory.application;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockTransferAnalysisResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockTransferAnalysisResponse.Line;
import org.myweb.flowmat.domain.inventory.api.dto.response.StockTransferAnalysisResponse.Route;
import org.myweb.flowmat.domain.inventory.repository.InventoryTransactionRepository;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Service;

/**
 * Adds up stock moved between places in the last days (docs/domain/stock-analysis.md "위치 간 이동"), from the transfer
 * ledger: every transfer, whether typed on the Stock tab or done as a warehouse task. Read only; nothing is stored.
 */
@Service
@RequiredArgsConstructor
public class StockTransferAnalysisService {

    private static final int MAX_DAYS = 365;

    private final ProjectAccessService projectAccessService;
    private final InventoryTransactionRepository inventoryTransactionRepository;
    private final CatalogQuery catalogQuery;

    public StockTransferAnalysisResponse transfers(String projectId, Integer days) {
        String id = projectId == null ? "" : projectId.trim();
        if (id.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "projectId is required.");
        }
        int span = days == null ? 30 : days;
        if (span < 1 || span > MAX_DAYS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "days must be between 1 and " + MAX_DAYS + ".");
        }
        projectAccessService.requireProjectReadAccess(id);
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(span);
        List<InventoryTransactionRepository.PlaceTransfer> moved = inventoryTransactionRepository.findPlaceTransfers(id, from);
        Map<String, CatalogItemView> items = catalogQuery.findItems(
            moved.stream().map(InventoryTransactionRepository.PlaceTransfer::getItemId).distinct().toList());

        // A route is its two places; either may be null, so the key is a list that allows it.
        Map<List<String>, List<Line>> byRoute = new LinkedHashMap<>();
        for (InventoryTransactionRepository.PlaceTransfer one : moved) {
            CatalogItemView item = items.get(one.getItemId());
            byRoute.computeIfAbsent(Arrays.asList(one.getFromLocation(), one.getToLocation()), key -> new ArrayList<>())
                .add(new Line(one.getItemId(), item == null ? null : item.itemCode(), item == null ? null : item.itemName(),
                    item == null ? null : item.unitCode(), one.getQuantity(), one.getMoves()));
        }
        Comparator<Line> busiestItem = Comparator.comparingLong(Line::moves).reversed()
            .thenComparing(Line::itemCode, Comparator.nullsLast(Comparator.naturalOrder()));
        List<Route> routes = byRoute.entrySet().stream()
            .map(entry -> new Route(entry.getKey().get(0), entry.getKey().get(1),
                entry.getValue().stream().mapToLong(Line::moves).sum(), entry.getValue().stream().sorted(busiestItem).toList()))
            .sorted(Comparator.comparingLong(Route::moves).reversed()
                .thenComparing(Route::fromLocation, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Route::toLocation, Comparator.nullsFirst(Comparator.naturalOrder())))
            .toList();
        return new StockTransferAnalysisResponse(span, from, to, routes);
    }
}
