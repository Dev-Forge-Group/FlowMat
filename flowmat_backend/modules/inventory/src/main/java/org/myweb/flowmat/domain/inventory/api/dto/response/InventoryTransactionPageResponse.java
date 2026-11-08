package org.myweb.flowmat.domain.inventory.api.dto.response;

import java.util.List;

/**
 * One page of the movement ledger, newest first (docs/domain/stock-ledger.md). Keyset-paged rather than
 * {@link org.myweb.flowmat.global.response.PageResponse}: new movements arriving between pages must not shift what the
 * next page returns, and no total count is needed.
 *
 * @param nextCursor pass it back as {@code cursor} for the next page; null when there is none.
 */
public record InventoryTransactionPageResponse(
    List<InventoryTransactionResponse> items,
    String nextCursor
) {
}
