package org.myweb.flowmat.domain.inventory.application;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogStorageQuery;
import org.myweb.flowmat.domain.inventory.repository.InventorySettingRepository;
import org.myweb.flowmat.domain.inventory.repository.StorageLocationRepository;
import org.myweb.flowmat.domain.inventory.repository.StorageLocationRepository.StorageConditionFields;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * Refuses stock coming into a listed place whose storage condition differs from the item's, when the project chose to
 * (docs/domain/storage-location.md SL5). A place without its own condition takes the nearest outer place's (SL1); either
 * side empty, or a place off the list, is not compared, and conditions compare as SL2 does.
 */
@Component
@RequiredArgsConstructor
public class StorageConditionGuard {

    private final InventorySettingRepository settings;
    private final StorageLocationRepository locations;
    private final CatalogStorageQuery catalogStorage;

    public void check(String projectId, String itemId, String location) {
        if (location == null || location.isBlank() || itemId == null) {
            return;
        }
        boolean refuse = settings.findById(projectId)
            .map(setting -> InventorySettingService.REFUSE.equals(setting.getStorageConditionPolicy())).orElse(false);
        if (!refuse) {
            return;
        }
        CatalogStorageQuery.ItemStorage item = catalogStorage.findStorage(List.of(itemId)).get(itemId);
        String wanted = key(item == null ? null : item.storageCondition());
        if (wanted == null) {
            return;
        }
        Map<String, StorageConditionFields> byId = new HashMap<>();
        StorageConditionFields place = null;
        for (StorageConditionFields one : locations.findStorageConditions(projectId, "N")) {
            byId.put(one.getLocationId(), one);
            if (one.getLocationCode().trim().equalsIgnoreCase(location.trim())) {
                place = one;
            }
        }
        String condition = null;
        Set<String> seen = new HashSet<>();
        for (StorageConditionFields at = place; at != null && seen.add(at.getLocationId()); at = byId.get(at.getParentLocationId())) {
            if (at.getStorageCondition() != null && !at.getStorageCondition().isBlank()) {
                condition = at.getStorageCondition();
                break;
            }
        }
        String held = key(condition);
        if (held != null && !held.equals(wanted)) {
            throw new BusinessException(ErrorCode.CONFLICT, item.itemCode() + " is kept \"" + item.storageCondition().trim() + "\"; "
                + place.getLocationCode() + " is \"" + condition.trim() + "\". This project refuses stock in a place with another storage condition.");
        }
    }

    /** Spaces tidied and case ignored, as SL2 compares; null when empty. */
    private static String key(String condition) {
        if (condition == null || condition.isBlank()) {
            return null;
        }
        return condition.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
