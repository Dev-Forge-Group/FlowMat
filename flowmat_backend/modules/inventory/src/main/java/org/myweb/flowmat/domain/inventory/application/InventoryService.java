package org.myweb.flowmat.domain.inventory.application;

import java.util.List;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryAdjustRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryResponse;

public interface InventoryService {

    List<InventoryResponse> listInventories(String projectId);

    InventoryResponse createInventory(InventoryAdjustRequest request);

    InventoryResponse getInventory(String inventoryId);

    InventoryResponse updateInventory(String inventoryId, InventoryAdjustRequest request);

    void deleteInventory(String inventoryId);
}
