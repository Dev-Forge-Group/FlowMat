package org.myweb.flowmat.domain.inventory.application;

import java.util.List;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryReversalRequest;
import org.myweb.flowmat.domain.inventory.api.dto.request.InventoryTransactionCreateRequest;
import org.myweb.flowmat.domain.inventory.api.dto.response.InventoryTransactionResponse;

public interface InventoryTransactionService {

    List<InventoryTransactionResponse> listTransactions(String projectId, String inventoryId);

    /** Applies one stock movement command; see docs/domain/inventory-bom-lot-contract.md §2. */
    InventoryTransactionResponse createTransaction(InventoryTransactionCreateRequest request);

    InventoryTransactionResponse getTransaction(String inventoryTransactionId);

    /** Adds the opposite of a transaction; see docs/domain/inventory-bom-lot-contract.md §3. */
    InventoryTransactionResponse reverseTransaction(String inventoryTransactionId, InventoryReversalRequest request);
}
