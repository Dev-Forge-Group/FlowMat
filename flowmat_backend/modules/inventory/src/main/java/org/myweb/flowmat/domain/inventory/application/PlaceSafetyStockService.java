package org.myweb.flowmat.domain.inventory.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.inventory.api.dto.request.PlaceSafetyStockRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.PlaceSafetyStockResponse;
import org.myweb.flowmat.domain.inventory.api.dto.response.ReorderLineResponse;
import org.myweb.flowmat.domain.inventory.domain.entity.Inventory;
import org.myweb.flowmat.domain.inventory.domain.entity.LotMaster;
import org.myweb.flowmat.domain.inventory.domain.entity.PlaceSafetyStock;
import org.myweb.flowmat.domain.inventory.domain.entity.StorageLocation;
import org.myweb.flowmat.domain.inventory.repository.InventoryRepository;
import org.myweb.flowmat.domain.inventory.repository.LotMasterRepository;
import org.myweb.flowmat.domain.inventory.repository.PlaceSafetyStockRepository;
import org.myweb.flowmat.domain.inventory.repository.StorageLocationRepository;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectCalendarQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safety stock for an item at one place of the location list, and the reorder list of that place
 * (docs/domain/stock-alert.md "위치별 재주문", PS1-PS6). The item-level safety stock stays on the item; a place watches only
 * the items given a safety stock there.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceSafetyStockService {

    private static final String NOT_DELETED = "N";
    /** The largest numeric(14,4). */
    private static final BigDecimal LARGEST = new BigDecimal("9999999999.9999");

    private final PlaceSafetyStockRepository placeSafetyStockRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final StorageLocationService storageLocationService;
    private final InventoryRepository inventoryRepository;
    private final LotMasterRepository lotMasterRepository;
    private final InventoryCatalogReferences catalogReferences;
    private final ProjectCalendarQuery projectCalendar;
    private final ProjectAccess projectAccessService;
    private final IdGenerator idGenerator;

    /** The project's place safety stocks, by place code and item code (PS3). */
    public List<PlaceSafetyStockResponse> list(String projectId) {
        String project = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(project);
        return responses(project);
    }

    /**
     * Sets an item's safety stock at a place, or removes it when none or zero is given (PS2). Both are the project's, and
     * active to set one. Answers the project's list as it is now.
     */
    @Transactional
    public List<PlaceSafetyStockResponse> set(PlaceSafetyStockRequest request) {
        String projectId = required(request.projectId(), "projectId");
        projectAccessService.requireProjectWriteAccess(projectId);
        BigDecimal quantity = request.safetyStockQty();
        boolean removing = quantity == null || quantity.signum() == 0;
        if (!removing && quantity.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The safety stock cannot be negative.");
        }
        if (!removing && (quantity.compareTo(LARGEST) > 0 || quantity.stripTrailingZeros().scale() > 4)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                "Give the safety stock as a number below 10,000,000,000 with at most 4 decimal places.");
        }
        String itemId = required(request.itemId(), "itemId");
        CatalogItemView item = catalogReferences.projectItems(projectId).stream()
            .filter(found -> found.itemId().equals(itemId))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The item was not found in this project."));
        StorageLocation place = storageLocationRepository.findByLocationIdAndDeletedYn(required(request.locationId(), "locationId"), NOT_DELETED)
            .filter(found -> found.getProjectId().equals(projectId))
            .orElseThrow(() -> new BusinessException(ErrorCode.BAD_REQUEST, "The place is not in this project's location list."));
        Optional<PlaceSafetyStock> existing = placeSafetyStockRepository.findByItemIdAndLocationId(item.itemId(), place.getLocationId());
        if (removing) {
            existing.ifPresent(placeSafetyStockRepository::delete);
            placeSafetyStockRepository.flush();
            return responses(projectId);
        }
        if (!ItemStatusRule.isActive(item.itemStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT,
                "Item " + item.itemCode() + " is " + item.itemStatus() + "; it takes no new stock, so it is not watched.");
        }
        if (!"Y".equals(place.getActiveYn())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Location " + place.getLocationCode() + " is inactive; activate it first.");
        }
        String actor = projectAccessService.requireCurrentUserId();
        PlaceSafetyStock row = existing.orElseGet(() -> {
            PlaceSafetyStock created = new PlaceSafetyStock();
            created.setPlaceSafetyStockId(idGenerator.generate());
            created.setProjectId(projectId);
            created.setItemId(item.itemId());
            created.setLocationId(place.getLocationId());
            created.setCreatedBy(actor);
            created.setCreatedAt(OffsetDateTime.now());
            return created;
        });
        if (existing.isPresent()) {
            row.setUpdatedBy(actor);
            row.setUpdatedAt(OffsetDateTime.now());
        }
        row.setSafetyStockQty(quantity);
        placeSafetyStockRepository.saveAndFlush(row);
        return responses(projectId);
    }

    /**
     * The reorder list of one place (PS4): the items given a safety stock at exactly that place, against their usable stock
     * there and in the places inside it (as the analysis counts a place, A5); only the short ones, biggest shortfall first.
     */
    public List<ReorderLineResponse> reorderAt(String projectId, String location) {
        String project = required(projectId, "projectId");
        projectAccessService.requireProjectReadAccess(project);
        StorageLocationService.Within place = storageLocationService.within(project, location);
        StorageLocation top = storageLocationRepository.findAllByProjectIdAndDeletedYn(project, NOT_DELETED).stream()
            .filter(found -> found.getLocationCode().equalsIgnoreCase(place.code()))
            .findFirst()
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        Map<String, BigDecimal> safety = placeSafetyStockRepository.findAllByProjectId(project).stream()
            .filter(row -> row.getLocationId().equals(top.getLocationId()))
            .collect(Collectors.toMap(PlaceSafetyStock::getItemId, PlaceSafetyStock::getSafetyStockQty));
        if (safety.isEmpty()) {
            return List.of();
        }
        // An inactive or discontinued item takes no new stock, so it is not suggested for ordering.
        List<CatalogItemView> watched = catalogReferences.items(safety.keySet()).stream()
            .filter(item -> ItemStatusRule.isActive(item.itemStatus()))
            .toList();
        if (watched.isEmpty()) {
            return List.of();
        }
        List<Inventory> rows = inventoryRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(project, NOT_DELETED);
        Map<String, LotMaster> lots = lotMasterRepository.findAllById(rows.stream().map(Inventory::getLotId).filter(Objects::nonNull)
                .distinct().toList()).stream()
            .collect(Collectors.toMap(LotMaster::getLotId, Function.identity(), (one, other) -> one));
        Map<String, BigDecimal> usable = ReorderLines.usable(rows, lots, projectCalendar.today(project),
            row -> row.getLocation() != null && place.codes().contains(StorageLocationService.key(row.getLocation())));
        Map<String, String> units = catalogReferences.unitCodes(watched.stream().map(CatalogItemView::unitId).filter(Objects::nonNull).distinct().toList());
        return ReorderLines.shortLines(watched, safety, usable, units, place.code());
    }

    private List<PlaceSafetyStockResponse> responses(String projectId) {
        List<PlaceSafetyStock> rows = placeSafetyStockRepository.findAllByProjectId(projectId);
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, StorageLocation> places = storageLocationRepository.findAllByProjectIdAndDeletedYn(projectId, NOT_DELETED).stream()
            .collect(Collectors.toMap(StorageLocation::getLocationId, Function.identity()));
        Map<String, CatalogItemView> items = catalogReferences.items(rows.stream().map(PlaceSafetyStock::getItemId).distinct().toList()).stream()
            .collect(Collectors.toMap(CatalogItemView::itemId, Function.identity(), (one, other) -> one));
        Map<String, String> units = catalogReferences.unitCodes(
            items.values().stream().map(CatalogItemView::unitId).filter(Objects::nonNull).distinct().toList());
        return rows.stream()
            // A place taken off the list keeps its rows, but they no longer show (PS6).
            .filter(row -> places.containsKey(row.getLocationId()))
            .map(row -> {
                CatalogItemView item = items.get(row.getItemId());
                return new PlaceSafetyStockResponse(row.getPlaceSafetyStockId(), row.getItemId(),
                    item == null ? null : item.itemCode(), item == null ? null : item.itemName(),
                    item == null ? null : units.get(item.unitId()), row.getLocationId(),
                    places.get(row.getLocationId()).getLocationCode(), row.getSafetyStockQty(),
                    row.getUpdatedBy() != null ? row.getUpdatedBy() : row.getCreatedBy(),
                    row.getUpdatedAt() != null ? row.getUpdatedAt() : row.getCreatedAt());
            })
            .sorted(Comparator.comparing(PlaceSafetyStockResponse::locationCode, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(line -> line.itemCode() == null ? "" : line.itemCode()))
            .toList();
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, field + " is required.");
        }
        return value.trim();
    }
}
