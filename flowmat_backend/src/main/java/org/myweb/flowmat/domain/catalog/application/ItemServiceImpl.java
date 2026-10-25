package org.myweb.flowmat.domain.catalog.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemCreateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemDetails;
import org.myweb.flowmat.domain.catalog.api.dto.request.ItemUpdateRequest;
import org.myweb.flowmat.domain.catalog.api.dto.response.ItemResponse;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.catalog.domain.entity.Item;
import org.myweb.flowmat.domain.catalog.repository.ItemRepository;
import org.myweb.flowmat.domain.catalog.repository.UnitMasterRepository;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockQuery;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ItemServiceImpl implements ItemService {

    private static final String NOT_DELETED = "N";
    private static final String DELETED = "Y";

    private final ItemRepository itemRepository;
    private final IdGenerator idGenerator;
    private final ProjectAccessService projectAccessService;
    private final UnitMasterRepository unitMasterRepository;
    private final StockQuery stockQuery;
    private final ItemCostHistoryService itemCostHistoryService;
    /** Other domains that still rely on an item; see {@link ItemUsageCheck}. */
    private final List<ItemUsageCheck> itemUsageChecks;
    private final CatalogQuery catalogQuery;

    /** Blank clears the unit; otherwise it must reference an active unit_master row. */
    private String requireActiveUnit(String unitId) {
        String normalized = trimToNull(unitId);
        if (normalized == null) {
            return null;
        }
        boolean active = unitMasterRepository.findById(normalized)
            .map(unit -> "Y".equalsIgnoreCase(unit.getActiveYn()))
            .orElse(false);
        if (!active) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Unit '" + normalized + "' does not exist or is inactive.");
        }
        return normalized;
    }

    @Override
    public List<ItemResponse> listItems(String projectId) {
        projectAccessService.requireProjectReadAccess(projectId);
        return itemRepository.findAllByProjectIdAndDeletedYnOrderByCreatedAtAsc(projectId, NOT_DELETED).stream()
            .map(ItemServiceImpl::toResponse)
            .toList();
    }

    @Override
    @Transactional
    public ItemResponse createItem(ItemCreateRequest request) {
        projectAccessService.requireProjectWriteAccess(request.projectId());
        // The table has no unique key on the code (V1); the code is how people and imports find an item, so keep it unique.
        if (itemRepository.existsByProjectIdAndItemCodeAndDeletedYn(request.projectId().trim(), request.itemCode().trim(), NOT_DELETED)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Item code " + request.itemCode().trim() + " already exists in this project.");
        }

        Item item = new Item();
        item.setItemId(idGenerator.generate());
        item.setProjectId(request.projectId().trim());
        item.setItemCode(request.itemCode().trim());
        item.setItemName(request.itemName().trim());
        item.setItemType(defaultIfBlank(request.itemType(), "generic"));
        item.setResourceCategory(defaultIfBlank(request.resourceCategory(), "material"));
        item.setResourceType(trimToNull(request.resourceType()));
        item.setUnitId(requireActiveUnit(request.unitId()));
        item.setItemStatus(hasText(request.itemStatus()) ? ItemStatusRule.requireKnown(request.itemStatus()) : ItemStatusRule.ACTIVE);
        item.setLotManageYn(yn(request.lotManageYn()));
        item.setLotReleaseRequiredYn(yn(request.lotReleaseRequiredYn()));
        item.setSafetyStockQty(requireNonNegative(request.safetyStockQty(), "Safety stock"));
        item.setLeadTimeDays(requireNonNegative(request.leadTimeDays(), "Lead time"));
        item.setUnitCost(requireNonNegative(request.unitCost(), "Unit cost"));
        if (request.details() != null) {
            applyDetails(item, request.details());
        }
        // Mapped now, so set the column's own default instead of writing null over it.
        item.setConversionRate(BigDecimal.ONE);
        if (request.purchaseUnit() != null || request.purchaseUnitQty() != null) {
            applyPurchaseUnit(item, request.purchaseUnit(), request.purchaseUnitQty());
        }
        item.setDeletedYn(NOT_DELETED);
        Item saved = itemRepository.save(item);
        itemCostHistoryService.record(saved, null, saved.getUnitCost());
        return toResponse(saved);
    }

    @Override
    public ItemResponse getItem(String itemId) {
        Item item = findActiveItem(itemId);
        projectAccessService.requireProjectReadAccess(item.getProjectId());
        return toResponse(item);
    }

    @Override
    @Transactional
    public ItemResponse updateItem(String itemId, ItemUpdateRequest request) {
        Item item = findActiveItem(itemId);
        projectAccessService.requireProjectWriteAccess(item.getProjectId());
        if (hasText(request.itemCode()) && !request.itemCode().trim().equals(item.getItemCode())) {
            // Items are referred to by id everywhere, so a code can change; it must stay unique like on create.
            String code = request.itemCode().trim();
            if (code.length() > 50) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Item code is longer than 50 characters.");
            }
            if (itemRepository.existsByProjectIdAndItemCodeAndDeletedYn(item.getProjectId(), code, NOT_DELETED)) {
                throw new BusinessException(ErrorCode.CONFLICT, "Item code " + code + " already exists in this project.");
            }
            item.setItemCode(code);
        }
        if (hasText(request.itemName())) {
            item.setItemName(request.itemName().trim());
        }
        if (hasText(request.itemType())) {
            item.setItemType(request.itemType().trim().toLowerCase());
        }
        if (hasText(request.resourceCategory())) {
            item.setResourceCategory(request.resourceCategory().trim().toLowerCase());
        }
        if (request.resourceType() != null) {
            item.setResourceType(trimToNull(request.resourceType()));
        }
        // Only a new unit must be active: an item keeps a unit deactivated since, and can still be edited.
        if (request.unitId() != null && !Objects.equals(trimToNull(request.unitId()), item.getUnitId())) {
            item.setUnitId(requireActiveUnit(request.unitId()));
        }
        // Like the unit, only a new status is checked, so an item saved with an older value can still be edited.
        if (hasText(request.itemStatus()) && !request.itemStatus().trim().equalsIgnoreCase(Objects.toString(item.getItemStatus(), ""))) {
            item.setItemStatus(ItemStatusRule.requireKnown(request.itemStatus()));
        }
        if (request.lotManageYn() != null && !yn(request.lotManageYn()).equals(item.getLotManageYn())) {
            // Existing stock rows were created under the old rule; switching would leave them inconsistent.
            if (stockQuery.hasStockRecords(item.getItemId())) {
                throw new BusinessException(ErrorCode.CONFLICT,
                    "LOT tracking can only change while the item has no stock records.");
            }
            item.setLotManageYn(yn(request.lotManageYn()));
        }
        // Applies to LOTs registered from now on; LOTs already registered keep their status (docs/domain/lot-release.md).
        if (request.lotReleaseRequiredYn() != null) {
            item.setLotReleaseRequiredYn(yn(request.lotReleaseRequiredYn()));
        }
        if (request.safetyStockQty() != null) {
            item.setSafetyStockQty(requireNonNegative(request.safetyStockQty(), "Safety stock"));
        }
        if (request.leadTimeDays() != null) {
            item.setLeadTimeDays(requireNonNegative(request.leadTimeDays(), "Lead time"));
        }
        BigDecimal costBefore = item.getUnitCost();
        if (request.unitCost() != null) {
            item.setUnitCost(requireNonNegative(request.unitCost(), "Unit cost"));
        }
        if (request.details() != null) {
            applyDetails(item, request.details());
        }
        if (request.purchaseUnit() != null || request.purchaseUnitQty() != null) {
            applyPurchaseUnit(item, request.purchaseUnit(), request.purchaseUnitQty());
        }
        Item saved = itemRepository.save(item);
        itemCostHistoryService.record(saved, costBefore, saved.getUnitCost());
        return toResponse(saved);
    }

    @Override
    @Transactional
    public void deleteItem(String itemId) {
        Item item = findActiveItem(itemId);
        projectAccessService.requireProjectOwnerAccess(item.getProjectId());
        CatalogItemView view = catalogQuery.findItems(List.of(item.getItemId())).get(item.getItemId());
        List<String> reasons = itemUsageChecks.stream()
            .map(check -> check.whyInUse(view))
            .flatMap(Optional::stream)
            .toList();
        if (!reasons.isEmpty()) {
            throw new BusinessException(ErrorCode.CONFLICT,
                item.getItemCode() + " cannot be deleted: " + String.join("; and ", reasons) + ".");
        }
        item.setDeletedYn(DELETED);
        item.setItemStatus("deleted");
        itemRepository.save(item);
    }

    /** Replaces every detail; blank clears. A barcode must not be another active item's. */
    private void applyDetails(Item item, ItemDetails details) {
        String barcode = trimToNull(details.barcode());
        if (barcode != null) {
            itemRepository.findFirstByProjectIdAndBarcodeAndDeletedYnAndItemIdNot(item.getProjectId(), barcode, NOT_DELETED, item.getItemId())
                .ifPresent(other -> {
                    throw new BusinessException(ErrorCode.CONFLICT,
                        "Barcode " + barcode + " is already used by item " + other.getItemCode() + ".");
                });
        }
        item.setItemGroup(trimToNull(details.itemGroup()));
        item.setSpec(trimToNull(details.spec()));
        item.setBarcode(barcode);
        item.setSku(trimToNull(details.sku()));
        item.setStorageCondition(trimToNull(details.storageCondition()));
        item.setItemDesc(trimToNull(details.description()));
    }

    /**
     * The unit the item is bought in and how many stock units one holds. A blank unit goes back to buying in the stock
     * unit; a quantity alone resizes the unit already set; a new unit without a quantity keeps the current size.
     */
    private static void applyPurchaseUnit(Item item, String unit, BigDecimal quantity) {
        if (unit != null && unit.isBlank()) {
            item.setPurchaseUnit(null);
            item.setConversionRate(BigDecimal.ONE);
            return;
        }
        String name = trimToNull(unit);
        if (name != null && name.length() > 20) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Purchase unit is longer than 20 characters.");
        }
        if (quantity != null) {
            if (quantity.signum() <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Purchase unit quantity must be more than 0.");
            }
            BigDecimal plain = quantity.stripTrailingZeros();
            if (plain.scale() > 8 || plain.precision() - plain.scale() > 10) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Purchase unit quantity takes at most 10 whole digits and 8 decimals.");
            }
            if (name == null && item.getPurchaseUnit() == null) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "Give the purchase unit with its quantity.");
            }
        }
        if (name != null) {
            item.setPurchaseUnit(name);
        }
        if (quantity != null) {
            item.setConversionRate(quantity);
        } else if (item.getConversionRate() == null) {
            item.setConversionRate(BigDecimal.ONE);
        }
    }

    private Item findActiveItem(String itemId) {
        return itemRepository.findByItemIdAndDeletedYn(itemId, NOT_DELETED)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static ItemResponse toResponse(Item item) {
        return new ItemResponse(
            item.getItemId(),
            item.getProjectId(),
            item.getItemCode(),
            item.getItemName(),
            item.getItemType(),
            item.getResourceCategory(),
            item.getResourceType(),
            item.getUnitId(),
            item.getItemStatus(),
            item.getLotManageYn(),
            item.getSafetyStockQty(),
            item.getLeadTimeDays(),
            item.getUnitCost(),
            new ItemDetails(item.getItemGroup(), item.getSpec(), item.getBarcode(), item.getSku(), item.getStorageCondition(),
                item.getItemDesc()),
            item.getPurchaseUnit(),
            item.getPurchaseUnit() == null ? null : item.getConversionRate(),
            "Y".equals(item.getLotReleaseRequiredYn()) ? "Y" : "N"
        );
    }

    private static BigDecimal requireNonNegative(BigDecimal value, String what) {
        if (value != null && value.signum() < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, what + " cannot be negative.");
        }
        return value;
    }

    private static Integer requireNonNegative(Integer value, String what) {
        if (value != null && value < 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, what + " cannot be negative.");
        }
        return value;
    }

    private static String yn(String value) {
        return "Y".equalsIgnoreCase(value == null ? null : value.trim()) ? "Y" : "N";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        return hasText(value) ? value.trim().toLowerCase() : defaultValue;
    }
}
