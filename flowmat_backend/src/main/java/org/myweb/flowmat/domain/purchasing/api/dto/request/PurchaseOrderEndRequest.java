package org.myweb.flowmat.domain.purchasing.api.dto.request;

/** Why a purchase order is cancelled, or why the rest of it is not coming (docs/domain/purchasing.md PO4). */
public record PurchaseOrderEndRequest(String reason) {
}
