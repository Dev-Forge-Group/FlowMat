package org.myweb.flowmat.domain.purchasing.api.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A purchase order with its lines and receipts (docs/domain/purchasing.md).
 *
 * @param endedBy       who received the last of it, closed it or cancelled it
 * @param orderValue    the known line values added up (PO14)
 * @param valueComplete every line has a unit price
 * @param dateChanges   expected dates moved while it was being received, oldest first (PO23)
 * @param returns       stock sent back to the supplier, oldest first (PO26-PO28)
 * @param approvedBy    the project owner who approved the draft as it is; null when not approved (PO34)
 * @param needsApproval a draft that is ordered only once approved, under the project's approval limit (PO34)
 * @param deliverTo     where it is to arrive; null when not said (PO39)
 * @param approvals     a draft's approvals in turn, each with when it was given (null while it waits); another order's as they were
 *                      given (PO50)
 */
public record PurchaseOrderResponse(String purchaseOrderId, String projectId, String orderNumber, String supplierName, String status,
                                    LocalDate expectedDate, String note, String createdBy, OffsetDateTime createdAt, String orderedBy,
                                    OffsetDateTime orderedAt, String endedBy, OffsetDateTime endedAt, String endReason, List<Line> lines,
                                    List<Receipt> receipts, BigDecimal orderValue, boolean valueComplete, List<DateChange> dateChanges,
                                    List<Return> returns, String approvedBy, OffsetDateTime approvedAt, boolean needsApproval,
                                    String deliverTo, List<Approval> approvals) {

    /**
     * @param unitCode  the item's stock unit, which the quantities are in
     * @param lineValue quantity × unit price, four decimals; null without a price
     * @param returnedQuantity sent back to the supplier; what was received stays (PO27)
     * @param supplierItemCode the supplier's code for the item, kept as the order was placed (PO45); null on a draft or without one
     * @param replacementQuantity sent back to be replaced on this line (PO46); receivedQuantity counts it too, so the order's own
     *                            quantity is received when receivedQuantity less this reaches it
     */
    public record Line(String lineId, int lineNo, String itemId, String itemCode, String itemName, String unitCode, boolean lotTracked,
                       BigDecimal quantity, BigDecimal receivedQuantity, BigDecimal remainingQuantity, LocalDate expectedDate, String note,
                       BigDecimal unitPrice, BigDecimal lineValue, BigDecimal returnedQuantity,
                       String supplierItemCode, BigDecimal replacementQuantity) {
    }

    public record Receipt(String receiptId, String lineId, String itemCode, BigDecimal quantity, String inventoryId, String location,
                          String lotNo, String receivedBy, OffsetDateTime receivedAt) {
    }

    /**
     * @param lineId   null for the order's own expected date
     * @param itemCode the line's item; null for the order
     */
    /**
     * @param inventoryId the stock record it left, with its place and LOT then
     * @param ncrNo       the NCR it went back for (PO42); null, as its id, when none
     * @param replacement the supplier replaces it on the same line (PO46)
     * @param creditAmount what the supplier credited for it (PO47), as last changed (PO52); null, as the note, who and when, until
     *                     that is recorded or once it is withdrawn
     * @param creditChanges every change to the credit, oldest first (PO52)
     */
    public record Return(String returnId, String lineId, String itemCode, BigDecimal quantity, String inventoryId, String location, String lotNo,
                         String reason, String returnedBy, OffsetDateTime returnedAt, String nonconformityId, String ncrNo,
                         boolean replacement, BigDecimal creditAmount, String creditNote, String creditedBy, OffsetDateTime creditedAt,
                         List<CreditChange> creditChanges) {
        @JsonProperty("creditAmountText")
        public String creditAmountText() {
            return creditAmount == null ? null : creditAmount.toPlainString();
        }
    }

    /** @param amount null when the credit was withdrawn */
    public record CreditChange(BigDecimal amount, String creditNote, String reason, String changedBy, OffsetDateTime changedAt) {
        @JsonProperty("amountText")
        public String amountText() {
            return amount == null ? null : amount.toPlainString();
        }
    }

    /**
     * @param fromValue    the order value it is needed from; null on an order that is no longer a draft
     * @param projectOwner the owner's approval from the approval limit
     * @param approvedAt   null while it waits
     */
    public record Approval(String approverId, BigDecimal fromValue, boolean projectOwner, OffsetDateTime approvedAt) {
    }

    public record DateChange(String changeId, String lineId, String itemCode, LocalDate previousDate, LocalDate expectedDate, String reason,
                             String changedBy, OffsetDateTime changedAt) {
    }
}
