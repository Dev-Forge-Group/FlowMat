package org.myweb.flowmat.domain.inventory.api.dto.response;

/** The two legs of one transfer; both carry referenceType "inventory_transfer" and the transfer id as referenceId. */
public record InventoryTransferResponse(
    String transferId,
    InventoryTransactionResponse out,
    InventoryTransactionResponse in
) {
}
