package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.ItemStatusRule;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.LotView;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockPlaceQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReceiptCommand;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockRecordQuery;
import org.myweb.flowmat.domain.inventory.application.publicapi.StockReturnCommand;
import org.myweb.flowmat.domain.notification.application.publicapi.NotificationCommand;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectMemberQuery;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseCreditChangeRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseCreditRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderDatesRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderEndRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseOrderRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseReceiptRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.request.PurchaseReturnRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.PurchaseOrderResponse;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCredit;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseCreditChange;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrder;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderApproval;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderDateChange;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseOrderLine;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseReceipt;
import org.myweb.flowmat.domain.purchasing.domain.entity.PurchaseReturn;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.myweb.flowmat.domain.purchasing.domain.entity.SupplierItem;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseCreditChangeRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseCreditRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderApprovalRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderDateChangeRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderLineRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseOrderRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseReceiptRepository;
import org.myweb.flowmat.domain.purchasing.repository.PurchaseReturnRepository;
import org.myweb.flowmat.domain.purchasing.repository.SupplierItemRepository;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.domain.quality.application.publicapi.NcrView;
import org.myweb.flowmat.domain.quality.application.publicapi.NonconformityQuery;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase orders (docs/architecture/adr/ADR-027-purchasing.md, docs/domain/purchasing.md PO1-PO7). A draft lists what to buy
 * from a supplier; ordering fixes it; what arrives is received into stock through inventory's receipt command, all lines of a
 * receipt or none. Every change holds the order's row lock.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderService {
    static final String DRAFT = "draft";
    static final String ORDERED = "ordered";
    static final String RECEIVED = "received";
    static final String CLOSED = "closed";
    static final String CANCELLED = "cancelled";
    static final List<String> STATUSES = List.of(DRAFT, ORDERED, RECEIVED, CLOSED, CANCELLED);
    /** The ledger reference of stock received on a purchase order. */
    public static final String RECEIPT_REFERENCE = "purchase_receipt";
    /** The ledger reference of stock sent back to the supplier (PO26). */
    public static final String RETURN_REFERENCE = "purchase_return";
    private static final int MAX_LINES = 100;
    private static final int MAX_SUPPLIER = 100;
    private static final int MAX_NOTE = 500;
    private static final int MAX_PLACE = 100;
    private static final int MAX_INTEGER_DIGITS = 10;
    private static final int MAX_CREDIT_NOTE = 100;
    /** A quantity times a price, each of ten whole digits at most. */
    private static final int MAX_AMOUNT_DIGITS = 20;
    private static final BigDecimal NO_VALUE = BigDecimal.ZERO.setScale(4);

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final PurchaseReceiptRepository receipts;
    private final PurchaseOrderDateChangeRepository dateChanges;
    private final PurchaseReturnRepository returns;
    private final PurchaseCreditRepository credits;
    private final PurchaseCreditChangeRepository creditChanges;
    private final PurchaseOrderApprovalRepository approvals;
    private final SupplierService supplierList;
    private final SupplierRepository suppliers;
    private final SupplierItemRepository supplierItems;
    private final PurchaseSettingService settings;
    private final NotificationCommand notifications;
    private final ProjectMemberQuery projectMembers;
    private final CatalogQuery catalog;
    private final StockReceiptCommand stock;
    private final StockReturnCommand stockBack;
    private final StockRecordQuery stockRecords;
    private final LotQuery lotQuery;
    private final NonconformityQuery ncrs;
    private final StockPlaceQuery places;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    /** Newest first; only one status when asked. */
    @Transactional(readOnly = true)
    public List<PurchaseOrderResponse> list(String projectId, String status) {
        String project = required(projectId, "projectId");
        access.requireProjectReadAccess(project);
        String wanted = status == null || status.isBlank() ? null : status(status);
        return responses(orders.findAllByProjectIdOrderByCreatedAtDescPurchaseOrderIdDesc(project).stream()
            .filter(order -> wanted == null || wanted.equals(order.getStatus()))
            .toList());
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(String purchaseOrderId) {
        PurchaseOrder order = orders.findById(purchaseOrderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectReadAccess(order.getProjectId());
        return response(order);
    }

    /** A new draft numbered PO-0001, PO-0002, ... in the project (PO1-PO2). */
    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderRequest request) {
        if (request == null) {
            throw bad("Send the purchase order.");
        }
        String projectId = required(request.projectId(), "projectId");
        access.requireProjectWriteAccess(projectId);
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseOrderId(ids.generate());
        order.setProjectId(projectId);
        order.setStatus(DRAFT);
        List<PurchaseOrderLine> newLines = fill(order, request);
        orders.lockKey("purchase-order|" + projectId);
        order.setOrderNumber(String.format(Locale.ROOT, "PO-%04d", orders.countByProjectId(projectId) + 1));
        order.setCreatedBy(access.requireCurrentUserId());
        order.setCreatedAt(OffsetDateTime.now());
        orders.saveAndFlush(order);
        lines.saveAllAndFlush(newLines);
        askForApproval(order, newLines);
        return response(order);
    }

    /** Replaces a draft whole (PO2). */
    @Transactional
    public PurchaseOrderResponse update(String purchaseOrderId, PurchaseOrderRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (!DRAFT.equals(order.getStatus())) {
            throw conflict(order, "only a draft changes");
        }
        if (request == null) {
            throw bad("Send the purchase order.");
        }
        List<PurchaseOrderLine> newLines = fill(order, request);
        lines.deleteAll(lines.findAllByPurchaseOrderIdOrderByLineNoAsc(order.getPurchaseOrderId()));
        // The old lines go first, so the new ones can take their numbers.
        lines.flush();
        order.setUpdatedBy(access.requireCurrentUserId());
        order.setUpdatedAt(OffsetDateTime.now());
        // A changed draft is approved again, by everyone it needs (PO34, PO50).
        order.setApprovedBy(null);
        order.setApprovedAt(null);
        approvals.deleteAll(approvals.findAllByPurchaseOrderId(order.getPurchaseOrderId()));
        approvals.flush();
        orders.saveAndFlush(order);
        lines.saveAllAndFlush(newLines);
        askForApproval(order, newLines);
        return response(order);
    }

    /** Sends a draft: its lines are fixed from now on and it can be received (PO3). */
    @Transactional
    public PurchaseOrderResponse order(String purchaseOrderId) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (!DRAFT.equals(order.getStatus())) {
            throw conflict(order, "only a draft is ordered");
        }
        List<PurchaseOrderLine> current = lines.findAllByPurchaseOrderIdOrderByLineNoAsc(order.getPurchaseOrderId());
        Map<String, CatalogItemView> items = catalog.findItems(current.stream().map(PurchaseOrderLine::getItemId).toList());
        // An item switched off after the draft was saved is not ordered.
        for (PurchaseOrderLine line : current) {
            CatalogItemView item = items.get(line.getItemId());
            if (item == null) {
                throw bad("Line " + line.getLineNo() + ": the item no longer exists.");
            }
            if (!ItemStatusRule.isActive(item.itemStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, ItemStatusRule.refusal(item.itemCode(), item.itemStatus(), "order it"));
            }
        }
        // A supplier switched off or renamed since the draft was saved is not ordered from (PO20).
        order.setSupplierName(supplierList.resolve(order.getProjectId(), order.getSupplierName()));
        if (order.getApprovedAt() == null) {
            // Every approval it needs now (PO34, PO50).
            Set<String> given = approvals.findAllByPurchaseOrderId(order.getPurchaseOrderId()).stream()
                .map(PurchaseOrderApproval::getApproverId).collect(Collectors.toSet());
            Optional<ApprovalRule> waiting = approvalsNeeded(current, settings.approvalRules(order.getProjectId())).stream()
                .filter(rule -> !given.contains(rule.approverId())).findFirst();
            if (waiting.isPresent()) {
                throw new BusinessException(ErrorCode.CONFLICT, order.getOrderNumber() + " needs " + approverName(waiting.get())
                    + "'s approval before it is ordered.");
            }
        }
        keepSupplierCodes(order, current);
        order.setStatus(ORDERED);
        order.setOrderedBy(access.requireCurrentUserId());
        order.setOrderedAt(OffsetDateTime.now());
        orders.saveAndFlush(order);
        return response(order);
    }

    /**
     * The code each line's supplier uses for its item, as the order is placed (PO45): the item's usual supplier's code when that
     * supplier is the order's, ignoring case; otherwise none.
     */
    private void keepSupplierCodes(PurchaseOrder order, List<PurchaseOrderLine> current) {
        Map<String, String> names = suppliers.findAllByProjectId(order.getProjectId()).stream()
            .collect(Collectors.toMap(Supplier::getSupplierId, Supplier::getSupplierName));
        Map<String, SupplierItem> usual = supplierItems.findAllByProjectId(order.getProjectId()).stream()
            .collect(Collectors.toMap(SupplierItem::getItemId, Function.identity(), (one, other) -> one));
        for (PurchaseOrderLine line : current) {
            SupplierItem one = usual.get(line.getItemId());
            String name = one == null ? null : names.get(one.getSupplierId());
            line.setSupplierItemCode(name != null && name.equalsIgnoreCase(order.getSupplierName()) ? one.getSupplierItemCode() : null);
        }
        lines.saveAll(current);
    }

    /**
     * Approves a draft as it is, in turn (PO34, PO50): only the first approver it still waits for, and once the last has, it can be
     * ordered and whoever saved it hears so. A draft that waits for no one may be approved by the project owner. Changing the
     * draft takes the approvals away; approving twice keeps the first.
     */
    @Transactional
    public PurchaseOrderResponse approve(String purchaseOrderId) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (!DRAFT.equals(order.getStatus())) {
            throw conflict(order, "only a draft is approved");
        }
        String actor = access.requireCurrentUserId();
        List<ApprovalRule> needed = approvalsNeeded(lines.findAllByPurchaseOrderIdOrderByLineNoAsc(order.getPurchaseOrderId()),
            settings.approvalRules(order.getProjectId()));
        boolean approver = needed.isEmpty() ? actor.equals(projectMembers.ownerOf(order.getProjectId()))
            : needed.stream().anyMatch(rule -> rule.approverId().equals(actor));
        if (!approver) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "You do not approve " + order.getOrderNumber() + ".");
        }
        Set<String> given = approvals.findAllByPurchaseOrderId(order.getPurchaseOrderId()).stream()
            .map(PurchaseOrderApproval::getApproverId).collect(Collectors.toSet());
        if (order.getApprovedAt() != null || given.contains(actor)) {
            return response(order);
        }
        List<ApprovalRule> waiting = needed.stream().filter(rule -> !given.contains(rule.approverId())).toList();
        if (!waiting.isEmpty() && !waiting.get(0).approverId().equals(actor)) {
            throw new BusinessException(ErrorCode.CONFLICT, order.getOrderNumber() + " waits for " + approverName(waiting.get(0))
                + "'s approval first.");
        }
        // As stored, so approvals read back from the database and those still in memory compare alike.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        PurchaseOrderApproval approval = new PurchaseOrderApproval();
        approval.setApprovalId(ids.generate());
        approval.setPurchaseOrderId(order.getPurchaseOrderId());
        approval.setApproverId(actor);
        approval.setApprovedAt(now);
        approvals.saveAndFlush(approval);
        if (waiting.size() > 1) {
            // The next approver hears that it is their turn (PO36, PO50).
            notifications.notifyAssignee(waiting.get(1).approverId(), actor, order.getProjectId(), "purchase_approval",
                order.getOrderNumber() + " needs your approval", order.getSupplierName() + ": approved by " + actor + "; you are next.",
                "purchase_order", order.getPurchaseOrderId());
            return response(order);
        }
        order.setApprovedBy(actor);
        order.setApprovedAt(now);
        orders.saveAndFlush(order);
        // Whoever saved the draft last hears that it can be ordered (PO36).
        notifications.notifyAssignee(order.getUpdatedBy() != null ? order.getUpdatedBy() : order.getCreatedBy(), order.getApprovedBy(),
            order.getProjectId(), "purchase_approved", order.getOrderNumber() + " was approved",
            order.getSupplierName() + ": it can be ordered now.", "purchase_order", order.getPurchaseOrderId());
        return response(order);
    }

    /** A draft, or an order with nothing received yet, is called off (PO4). */
    @Transactional
    public PurchaseOrderResponse cancel(String purchaseOrderId, PurchaseOrderEndRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        String reason = reason(request);
        if (!DRAFT.equals(order.getStatus()) && !ORDERED.equals(order.getStatus())) {
            throw conflict(order, "it cannot be cancelled");
        }
        if (receipts.existsByPurchaseOrderId(order.getPurchaseOrderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, order.getOrderNumber() + " has stock received on it; close it instead.");
        }
        return end(order, CANCELLED, reason);
    }

    /** The rest of an order that was partly received is not coming (PO4). */
    @Transactional
    public PurchaseOrderResponse close(String purchaseOrderId, PurchaseOrderEndRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        String reason = reason(request);
        if (!ORDERED.equals(order.getStatus())) {
            throw conflict(order, "only an order being received is closed");
        }
        if (!receipts.existsByPurchaseOrderId(order.getPurchaseOrderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Nothing was received on " + order.getOrderNumber() + "; cancel it instead.");
        }
        return end(order, CLOSED, reason);
    }

    /**
     * Receives what arrived on an order into stock, every line or none (PO5-PO6). The same requestId with the same lines answers
     * as the order is now; with other lines it is refused.
     */
    @Transactional
    public PurchaseOrderResponse receive(String purchaseOrderId, PurchaseReceiptRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (request == null || request.requestId() == null) {
            throw bad("requestId is required.");
        }
        List<PurchaseReceiptRequest.Line> requested = request.lines() == null ? List.of() : request.lines();
        List<PurchaseReceipt> earlier = receipts.findAllByPurchaseOrderIdAndRequestId(order.getPurchaseOrderId(), request.requestId());
        if (!earlier.isEmpty()) {
            if (sameReceipt(earlier, requested)) {
                return response(order);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different receipt.");
        }
        if (!ORDERED.equals(order.getStatus())) {
            throw conflict(order, "only an ordered one is received");
        }
        if (requested.isEmpty()) {
            throw bad("Receive at least one line.");
        }
        List<PurchaseOrderLine> current = lines.findAllByPurchaseOrderIdOrderByLineNoAsc(order.getPurchaseOrderId());
        Map<String, PurchaseOrderLine> byId = current.stream().collect(Collectors.toMap(PurchaseOrderLine::getLineId, Function.identity()));
        Map<String, CatalogItemView> items = catalog.findItems(current.stream().map(PurchaseOrderLine::getItemId).toList());
        Set<String> seen = new HashSet<>();
        String actor = access.requireCurrentUserId();
        // As stored, so receipts read back from the database and those still in memory compare alike.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        String note = "Received on " + order.getOrderNumber() + " from " + order.getSupplierName();
        for (PurchaseReceiptRequest.Line wanted : requested) {
            if (wanted == null) {
                throw bad("lines must not contain null entries.");
            }
            PurchaseOrderLine line = wanted.lineId() == null ? null : byId.get(wanted.lineId());
            if (line == null) {
                throw bad("A line to receive is not on " + order.getOrderNumber() + ".");
            }
            CatalogItemView item = items.get(line.getItemId());
            String code = item == null ? "Line " + line.getLineNo() : item.itemCode();
            if (!seen.add(line.getLineId())) {
                throw bad(code + " appears twice; combine the lines.");
            }
            BigDecimal quantity = quantity(wanted.quantity(), code + " quantity");
            if (quantity.compareTo(line.remaining()) > 0) {
                throw bad("Only " + line.remaining().stripTrailingZeros().toPlainString() + " of " + code + " is left to receive.");
            }
            String location = text(wanted.location(), "location", MAX_PLACE);
            String lotNo = text(wanted.lotNo(), "lotNo", MAX_PLACE);
            String receiptId = ids.generate();
            String inventoryId = stock.receive(new StockReceiptCommand.StockReceipt(order.getProjectId(), line.getItemId(), quantity, location,
                lotNo, date(wanted.expiryDate(), "expiryDate"), RECEIPT_REFERENCE, receiptId, note));
            PurchaseReceipt receipt = new PurchaseReceipt();
            receipt.setReceiptId(receiptId);
            receipt.setPurchaseOrderId(order.getPurchaseOrderId());
            receipt.setLineId(line.getLineId());
            receipt.setRequestId(request.requestId());
            receipt.setQuantity(quantity);
            receipt.setInventoryId(inventoryId);
            receipt.setLocation(location);
            receipt.setLotNo(lotNo);
            receipt.setReceivedBy(actor);
            receipt.setReceivedAt(now);
            receipts.save(receipt);
            line.setReceivedQuantity(line.getReceivedQuantity().add(quantity));
            lines.save(line);
        }
        if (current.stream().allMatch(line -> line.remaining().signum() <= 0)) {
            order.setStatus(RECEIVED);
            order.setEndedBy(actor);
            order.setEndedAt(now);
        }
        receipts.flush();
        lines.flush();
        orders.saveAndFlush(order);
        return response(order);
    }

    /**
     * Sends stock received on a line back to the supplier (PO26-PO28): from a stock record of the line's item, for a LOT item a
     * LOT received on that line, at most what was received less what went back already. The stock leaves as a decreasing
     * adjustment, so quarantined stock can go; what was received stays. Without a replacement the order's status stays too; with
     * one (PO46) that much is to come again on the line, and a received order is being received again. A closed order is not
     * replaced on. The same requestId with the same return answers as the order is now; with another it is refused.
     */
    @Transactional
    public PurchaseOrderResponse giveBack(String purchaseOrderId, PurchaseReturnRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (request == null || request.requestId() == null) {
            throw bad("requestId is required.");
        }
        Optional<PurchaseReturn> earlier = returns.findByPurchaseOrderIdAndRequestId(order.getPurchaseOrderId(), request.requestId());
        if (earlier.isPresent()) {
            if (sameReturn(earlier.get(), request)) {
                return response(order);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "requestId already belongs to a different return.");
        }
        String reason = text(request.reason(), "reason", MAX_NOTE);
        if (reason == null) {
            throw bad("Give a reason.");
        }
        if (!receipts.existsByPurchaseOrderId(order.getPurchaseOrderId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "Nothing was received on " + order.getOrderNumber() + " to send back.");
        }
        boolean replacement = Boolean.TRUE.equals(request.replacement());
        if (replacement && CLOSED.equals(order.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, order.getOrderNumber() + " is closed; order the replacement anew.");
        }
        PurchaseOrderLine line = request.lineId() == null ? null : lines.findById(request.lineId())
            .filter(one -> one.getPurchaseOrderId().equals(order.getPurchaseOrderId())).orElse(null);
        if (line == null) {
            throw bad("A line to send back is not on " + order.getOrderNumber() + ".");
        }
        CatalogItemView item = catalog.findItems(List.of(line.getItemId())).get(line.getItemId());
        String code = item == null ? "Line " + line.getLineNo() : item.itemCode();
        BigDecimal quantity = quantity(request.quantity(), code + " quantity");
        BigDecimal open = line.getReceivedQuantity().subtract(returns.findAllByLineId(line.getLineId()).stream()
            .map(PurchaseReturn::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add));
        if (quantity.compareTo(open) > 0) {
            throw bad("Only " + open.stripTrailingZeros().toPlainString() + " of " + code + " received on " + order.getOrderNumber()
                + " can go back.");
        }
        String inventoryId = required(request.inventoryId(), "inventoryId");
        StockRecordQuery.StockIdentity record = stockRecords.findProjectStock(order.getProjectId(), inventoryId)
            .orElseThrow(() -> bad("The stock record to send back was not found in this project."));
        if (!line.getItemId().equals(record.itemId())) {
            throw bad("Send " + code + " back from a stock record of " + code + ".");
        }
        String lotNo = record.lotId() == null ? null
            : lotQuery.findProjectLot(order.getProjectId(), record.lotId()).map(LotView::lotNo).orElse(null);
        if (item != null && "Y".equals(item.lotManageYn())) {
            // A LOT item goes back as a LOT that came on this line.
            Set<String> came = receipts.findAllByPurchaseOrderIdInOrderByReceivedAtAscReceiptIdAsc(List.of(order.getPurchaseOrderId())).stream()
                .filter(receipt -> receipt.getLineId().equals(line.getLineId()) && receipt.getLotNo() != null)
                .map(receipt -> receipt.getLotNo().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
            if (lotNo == null || !came.contains(lotNo.toLowerCase(Locale.ROOT))) {
                throw bad("Send back a LOT of " + code + " received on " + order.getOrderNumber() + ".");
            }
        }
        NcrView ncr = returnedFor(order, request.nonconformityId(), line, code, record.lotId());
        String returnId = ids.generate();
        stockBack.giveBack(new StockReturnCommand.StockReturn(order.getProjectId(), inventoryId, quantity, RETURN_REFERENCE, returnId,
            "Returned to " + order.getSupplierName() + " on " + order.getOrderNumber() + ": " + reason));
        record = stockRecords.findProjectStock(order.getProjectId(), inventoryId)
            .orElseThrow(() -> bad("The stock record to send back was not found in this project."));
        PurchaseReturn saved = new PurchaseReturn();
        saved.setReturnId(returnId);
        saved.setPurchaseOrderId(order.getPurchaseOrderId());
        saved.setLineId(line.getLineId());
        saved.setRequestId(request.requestId());
        saved.setQuantity(quantity);
        saved.setInventoryId(inventoryId);
        saved.setLocation(record.location());
        saved.setLotNo(lotNo);
        saved.setReason(reason);
        saved.setNonconformityId(ncr == null ? null : ncr.nonconformityId());
        saved.setNcrNo(ncr == null ? null : ncr.ncrNo());
        saved.setReplacementYn(replacement ? "Y" : "N");
        saved.setReturnedBy(access.requireCurrentUserId());
        // As stored, so returns read back from the database and those still in memory sort alike.
        saved.setReturnedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        returns.saveAndFlush(saved);
        if (replacement) {
            // It comes again on the same line, so it counts on order and the order is received once it has come (PO46).
            line.setReplacementQuantity(line.getReplacementQuantity().add(quantity));
            lines.saveAndFlush(line);
            if (RECEIVED.equals(order.getStatus())) {
                order.setStatus(ORDERED);
                order.setEndedBy(null);
                order.setEndedAt(null);
                orders.saveAndFlush(order);
            }
        }
        return response(order);
    }

    /**
     * Keeps what the supplier credited for a return of the order, once (PO47): an amount of 0 or more and the supplier's credit
     * note if any. Any return can be credited, also one sent for replacement whose replacement will not come. The same credit
     * again answers as the order is now; another one is refused.
     */
    @Transactional
    public PurchaseOrderResponse credit(String purchaseOrderId, String returnId, PurchaseCreditRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        PurchaseReturn back = returnId == null ? null : returns.findById(returnId)
            .filter(one -> one.getPurchaseOrderId().equals(order.getPurchaseOrderId())).orElse(null);
        if (back == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (request == null) {
            throw bad("Send the credit.");
        }
        BigDecimal amount = amount(request.amount());
        String note = text(request.creditNote(), "creditNote", MAX_CREDIT_NOTE);
        Optional<PurchaseCredit> earlier = credits.findById(back.getReturnId());
        if (earlier.isPresent()) {
            if (earlier.get().getAmount().compareTo(amount) == 0 && Objects.equals(earlier.get().getCreditNote(), note)) {
                return response(order);
            }
            throw new BusinessException(ErrorCode.CONFLICT, "That return on " + order.getOrderNumber() + " was already credited.");
        }
        PurchaseCredit credit = new PurchaseCredit();
        credit.setReturnId(back.getReturnId());
        credit.setPurchaseOrderId(order.getPurchaseOrderId());
        credit.setAmount(amount);
        credit.setCreditNote(note);
        credit.setCreditedBy(access.requireCurrentUserId());
        // As stored, so credits read back from the database and those still in memory compare alike.
        credit.setCreditedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        credits.saveAndFlush(credit);
        return response(order);
    }

    /**
     * Changes what was kept as credited for a return, with a reason (PO52): another amount and credit note, or none when it was
     * not credited after all, which withdraws it so it is owed again. Every change is kept and the latest stands; the same as now
     * changes nothing.
     */
    @Transactional
    public PurchaseOrderResponse changeCredit(String purchaseOrderId, String returnId, PurchaseCreditChangeRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        PurchaseReturn back = returnId == null ? null : returns.findById(returnId)
            .filter(one -> one.getPurchaseOrderId().equals(order.getPurchaseOrderId())).orElse(null);
        if (back == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        if (request == null) {
            throw bad("Send the change.");
        }
        BigDecimal amount = request.amount() == null ? null : amount(request.amount());
        String note = amount == null ? null : text(request.creditNote(), "creditNote", MAX_CREDIT_NOTE);
        String reason = text(request.reason(), "reason", MAX_NOTE);
        if (reason == null) {
            throw bad("Give a reason.");
        }
        PurchaseCredit credit = credits.findById(back.getReturnId()).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT,
            "Nothing was credited for that return on " + order.getOrderNumber() + "; record the credit first."));
        List<PurchaseCreditChange> earlier = creditChanges.findAllByReturnIdOrderByChangedAtAscChangeIdAsc(back.getReturnId());
        PurchaseCreditChange last = earlier.isEmpty() ? null : earlier.get(earlier.size() - 1);
        BigDecimal standing = last == null ? credit.getAmount() : last.getAmount();
        String standingNote = last == null ? credit.getCreditNote() : last.getCreditNote();
        if ((standing == null ? amount == null : amount != null && standing.compareTo(amount) == 0) && Objects.equals(standingNote, note)) {
            return response(order);
        }
        PurchaseCreditChange change = new PurchaseCreditChange();
        change.setChangeId(ids.generate());
        change.setReturnId(back.getReturnId());
        change.setPurchaseOrderId(order.getPurchaseOrderId());
        change.setAmount(amount);
        change.setCreditNote(note);
        change.setReason(reason);
        change.setChangedBy(access.requireCurrentUserId());
        // As stored, so changes read back from the database and those still in memory sort alike.
        change.setChangedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        creditChanges.saveAndFlush(change);
        return response(order);
    }

    private static boolean sameReturn(PurchaseReturn earlier, PurchaseReturnRequest request) {
        return earlier.getLineId().equals(request.lineId())
            && request.inventoryId() != null && earlier.getInventoryId().equals(request.inventoryId().trim())
            && request.quantity() != null && earlier.getQuantity().compareTo(request.quantity()) == 0
            && request.reason() != null && earlier.getReason().equals(request.reason().trim())
            && Objects.equals(earlier.getNonconformityId(), ncrId(request.nonconformityId()))
            && earlier.getReplacementYn().equals(Boolean.TRUE.equals(request.replacement()) ? "Y" : "N");
    }

    private static String ncrId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The NCR a return names, if any: open, of the order's project, set to return to supplier, and about the line's item and
     * the LOT going back when it names them (PO42). Read without a lock: an NCR closed at the same moment may still be named.
     */
    private NcrView returnedFor(PurchaseOrder order, String nonconformityId, PurchaseOrderLine line, String code, String lotId) {
        String id = text(nonconformityId, "nonconformityId", 50);
        if (id == null) {
            return null;
        }
        NcrView ncr = ncrs.findProjectNcr(order.getProjectId(), id).orElseThrow(() -> bad("The NCR was not found in this project."));
        if (!"open".equals(ncr.status())) {
            throw new BusinessException(ErrorCode.CONFLICT, ncr.ncrNo() + " is " + ncr.status() + "; only an open NCR sends stock back.");
        }
        if (!"return_to_supplier".equals(ncr.disposition())) {
            throw new BusinessException(ErrorCode.CONFLICT, ncr.ncrNo() + " is not set to return to supplier.");
        }
        if (ncr.itemId() != null && !ncr.itemId().equals(line.getItemId())) {
            throw bad(ncr.ncrNo() + " is about another item than " + code + ".");
        }
        if (ncr.lotId() != null && !ncr.lotId().equals(lotId)) {
            throw bad("Send back the LOT " + ncr.ncrNo() + " is about.");
        }
        return ncr;
    }

    /**
     * Moves the expected dates of an order being received, with a reason; every change is kept (PO22-PO23). A fully received
     * line keeps its date. Sending the dates it already has changes nothing, so a repeat after a lost answer is harmless.
     */
    @Transactional
    public PurchaseOrderResponse changeDates(String purchaseOrderId, PurchaseOrderDatesRequest request) {
        PurchaseOrder order = lock(purchaseOrderId);
        access.requireProjectWriteAccess(order.getProjectId());
        if (request == null) {
            throw bad("Send the expected dates.");
        }
        String reason = text(request.reason(), "reason", MAX_NOTE);
        if (reason == null) {
            throw bad("Give a reason.");
        }
        if (!ORDERED.equals(order.getStatus())) {
            throw conflict(order, "only the dates of an order being received move");
        }
        List<PurchaseOrderLine> current = lines.findAllByPurchaseOrderIdOrderByLineNoAsc(order.getPurchaseOrderId());
        Map<String, PurchaseOrderLine> byId = current.stream().collect(Collectors.toMap(PurchaseOrderLine::getLineId, Function.identity()));
        Map<String, CatalogItemView> items = catalog.findItems(current.stream().map(PurchaseOrderLine::getItemId).toList());
        String actor = access.requireCurrentUserId();
        // As stored, so changes read back from the database and those still in memory sort alike.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
        List<PurchaseOrderDateChange> changes = new ArrayList<>();
        LocalDate orderDate = date(request.expectedDate(), "expectedDate");
        if (!Objects.equals(orderDate, order.getExpectedDate())) {
            changes.add(dateChange(order, null, order.getExpectedDate(), orderDate, reason, actor, now));
            order.setExpectedDate(orderDate);
        }
        Set<String> seen = new HashSet<>();
        for (PurchaseOrderDatesRequest.Line wanted : request.lines() == null ? List.<PurchaseOrderDatesRequest.Line>of() : request.lines()) {
            if (wanted == null) {
                throw bad("lines must not contain null entries.");
            }
            PurchaseOrderLine line = wanted.lineId() == null ? null : byId.get(wanted.lineId());
            if (line == null) {
                throw bad("A line to move is not on " + order.getOrderNumber() + ".");
            }
            CatalogItemView item = items.get(line.getItemId());
            String code = item == null ? "Line " + line.getLineNo() : item.itemCode();
            if (!seen.add(line.getLineId())) {
                throw bad(code + " appears twice; send each line once.");
            }
            LocalDate lineDate = date(wanted.expectedDate(), "lines.expectedDate");
            if (Objects.equals(lineDate, line.getExpectedDate())) {
                continue;
            }
            if (line.remaining().signum() <= 0) {
                throw bad(code + " is fully received; its date stays.");
            }
            changes.add(dateChange(order, line.getLineId(), line.getExpectedDate(), lineDate, reason, actor, now));
            line.setExpectedDate(lineDate);
            lines.save(line);
        }
        if (!changes.isEmpty()) {
            dateChanges.saveAll(changes);
            order.setUpdatedBy(actor);
            order.setUpdatedAt(now);
        }
        dateChanges.flush();
        lines.flush();
        orders.saveAndFlush(order);
        return response(order);
    }

    private PurchaseOrderDateChange dateChange(PurchaseOrder order, String lineId, LocalDate previous, LocalDate expected, String reason,
                                               String actor, OffsetDateTime at) {
        PurchaseOrderDateChange change = new PurchaseOrderDateChange();
        change.setChangeId(ids.generate());
        change.setPurchaseOrderId(order.getPurchaseOrderId());
        change.setLineId(lineId);
        change.setPreviousDate(previous);
        change.setExpectedDate(expected);
        change.setReason(reason);
        change.setChangedBy(actor);
        change.setChangedAt(at);
        return change;
    }

    /** Header fields and lines from the request, checked; nothing is saved here. */
    private List<PurchaseOrderLine> fill(PurchaseOrder order, PurchaseOrderRequest request) {
        String supplier = text(request.supplierName(), "supplierName", MAX_SUPPLIER);
        if (supplier == null) {
            throw bad("Name the supplier.");
        }
        // Once the project lists suppliers, the order names an active one as the list spells it (PO20).
        order.setSupplierName(supplierList.resolve(order.getProjectId(), supplier));
        order.setExpectedDate(date(request.expectedDate(), "expectedDate"));
        // A listed place when the project lists any, as receipts are (PO39).
        order.setDeliverTo(places.placeFor(order.getProjectId(), text(request.deliverTo(), "deliverTo", MAX_PLACE)));
        order.setNote(text(request.note(), "note", MAX_NOTE));
        List<PurchaseOrderRequest.Line> requested = request.lines() == null ? List.of() : request.lines();
        if (requested.isEmpty()) {
            throw bad("Add at least one line.");
        }
        if (requested.size() > MAX_LINES) {
            throw bad("A purchase order has at most " + MAX_LINES + " lines.");
        }
        if (requested.stream().anyMatch(Objects::isNull)) {
            throw bad("lines must not contain null entries.");
        }
        Map<String, CatalogItemView> items = catalog.findItems(requested.stream().map(PurchaseOrderRequest.Line::itemId)
            .filter(Objects::nonNull).map(String::trim).toList());
        Set<String> seen = new HashSet<>();
        List<PurchaseOrderLine> result = new ArrayList<>();
        for (int index = 0; index < requested.size(); index++) {
            PurchaseOrderRequest.Line wanted = requested.get(index);
            String where = "Line " + (index + 1);
            String itemId = wanted.itemId() == null ? null : wanted.itemId().trim();
            CatalogItemView item = itemId == null ? null : items.get(itemId);
            if (item == null || !order.getProjectId().equals(item.projectId())) {
                throw bad(where + ": choose an item of this project.");
            }
            if (!ItemStatusRule.isActive(item.itemStatus())) {
                throw new BusinessException(ErrorCode.CONFLICT, ItemStatusRule.refusal(item.itemCode(), item.itemStatus(), "order it"));
            }
            if (!seen.add(itemId)) {
                throw bad(item.itemCode() + " is on two lines; combine them.");
            }
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setLineId(ids.generate());
            line.setPurchaseOrderId(order.getPurchaseOrderId());
            line.setLineNo(index + 1);
            line.setItemId(itemId);
            line.setQuantity(quantity(wanted.quantity(), where + " quantity"));
            line.setReceivedQuantity(BigDecimal.ZERO);
            line.setExpectedDate(date(wanted.expectedDate(), "lines.expectedDate"));
            line.setNote(text(wanted.note(), "lines.note", MAX_NOTE));
            line.setUnitPrice(price(wanted.unitPrice(), where + " unit price"));
            result.add(line);
        }
        return result;
    }

    private PurchaseOrderResponse end(PurchaseOrder order, String status, String reason) {
        order.setStatus(status);
        order.setEndedBy(access.requireCurrentUserId());
        order.setEndedAt(OffsetDateTime.now());
        order.setEndReason(reason);
        orders.saveAndFlush(order);
        return response(order);
    }

    private static boolean sameReceipt(List<PurchaseReceipt> earlier, List<PurchaseReceiptRequest.Line> requested) {
        if (requested.size() != earlier.size() || requested.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        Set<ReceiptKey> before = earlier.stream()
            .map(receipt -> receiptKey(receipt.getLineId(), receipt.getQuantity(), receipt.getLocation(), receipt.getLotNo()))
            .collect(Collectors.toSet());
        Set<ReceiptKey> now = requested.stream()
            .map(line -> receiptKey(line.lineId(), line.quantity(), line.location(), line.lotNo()))
            .collect(Collectors.toSet());
        return before.equals(now);
    }

    private record ReceiptKey(String lineId, String quantity, String location, String lotNo) { }

    private static ReceiptKey receiptKey(String lineId, BigDecimal quantity, String location, String lotNo) {
        return new ReceiptKey(lineId, quantity == null ? "" : quantity.stripTrailingZeros().toPlainString(),
            location == null ? "" : location.trim(), lotNo == null ? "" : lotNo.trim());
    }

    private PurchaseOrderResponse response(PurchaseOrder order) {
        return responses(List.of(order)).get(0);
    }

    private List<PurchaseOrderResponse> responses(List<PurchaseOrder> found) {
        if (found.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = found.stream().map(PurchaseOrder::getPurchaseOrderId).toList();
        Map<String, List<PurchaseOrderLine>> linesByOrder = lines.findAllByPurchaseOrderIdInOrderByLineNoAsc(orderIds).stream()
            .collect(Collectors.groupingBy(PurchaseOrderLine::getPurchaseOrderId));
        Map<String, List<PurchaseReceipt>> receiptsByOrder = receipts.findAllByPurchaseOrderIdInOrderByReceivedAtAscReceiptIdAsc(orderIds)
            .stream().collect(Collectors.groupingBy(PurchaseReceipt::getPurchaseOrderId));
        Map<String, List<PurchaseOrderDateChange>> changesByOrder = dateChanges.findAllByPurchaseOrderIdIn(orderIds).stream()
            .collect(Collectors.groupingBy(PurchaseOrderDateChange::getPurchaseOrderId));
        Map<String, List<PurchaseReturn>> returnsByOrder = returns.findAllByPurchaseOrderIdIn(orderIds).stream()
            .collect(Collectors.groupingBy(PurchaseReturn::getPurchaseOrderId));
        Map<String, BigDecimal> returnedByLine = returnsByOrder.values().stream().flatMap(List::stream)
            .collect(Collectors.toMap(PurchaseReturn::getLineId, PurchaseReturn::getQuantity, BigDecimal::add));
        Map<String, PurchaseCredit> creditByReturn = credits.findAllByPurchaseOrderIdIn(orderIds).stream()
            .collect(Collectors.toMap(PurchaseCredit::getReturnId, Function.identity()));
        Map<String, List<PurchaseCreditChange>> changesByReturn = creditChanges.findAllByPurchaseOrderIdIn(orderIds).stream()
            .sorted(Comparator.comparing(PurchaseCreditChange::getChangedAt).thenComparing(PurchaseCreditChange::getChangeId))
            .collect(Collectors.groupingBy(PurchaseCreditChange::getReturnId));
        Collection<String> itemIds = linesByOrder.values().stream().flatMap(List::stream).map(PurchaseOrderLine::getItemId)
            .collect(Collectors.toSet());
        Map<String, CatalogItemView> items = itemIds.isEmpty() ? Map.of() : catalog.findItems(itemIds);
        Map<String, List<ApprovalRule>> rules = new HashMap<>();
        found.stream().map(PurchaseOrder::getProjectId).distinct().forEach(project -> rules.put(project, settings.approvalRules(project)));
        Map<String, List<PurchaseOrderApproval>> approvalsByOrder = approvals.findAllByPurchaseOrderIdIn(orderIds).stream()
            .collect(Collectors.groupingBy(PurchaseOrderApproval::getPurchaseOrderId));
        return found.stream().map(order -> {
            List<PurchaseOrderLine> orderLines = linesByOrder.getOrDefault(order.getPurchaseOrderId(), List.of());
            List<ApprovalRule> needed = DRAFT.equals(order.getStatus()) ? approvalsNeeded(orderLines, rules.get(order.getProjectId())) : List.of();
            Map<String, String> codes = orderLines.stream().collect(Collectors.toMap(PurchaseOrderLine::getLineId,
                line -> items.containsKey(line.getItemId()) ? items.get(line.getItemId()).itemCode() : line.getItemId()));
            // Lines received together share a time, so they follow the order's line numbers.
            Map<String, Integer> lineNos = orderLines.stream().collect(Collectors.toMap(PurchaseOrderLine::getLineId, PurchaseOrderLine::getLineNo));
            List<PurchaseReceipt> orderReceipts = receiptsByOrder.getOrDefault(order.getPurchaseOrderId(), List.of()).stream()
                .sorted(Comparator.comparing(PurchaseReceipt::getReceivedAt)
                    .thenComparing(receipt -> lineNos.getOrDefault(receipt.getLineId(), Integer.MAX_VALUE))
                    .thenComparing(PurchaseReceipt::getReceiptId))
                .toList();
            return new PurchaseOrderResponse(order.getPurchaseOrderId(), order.getProjectId(), order.getOrderNumber(), order.getSupplierName(),
                order.getStatus(), order.getExpectedDate(), order.getNote(), order.getCreatedBy(), order.getCreatedAt(), order.getOrderedBy(),
                order.getOrderedAt(), order.getEndedBy(), order.getEndedAt(), order.getEndReason(),
                orderLines.stream().map(line -> {
                    CatalogItemView item = items.get(line.getItemId());
                    return new PurchaseOrderResponse.Line(line.getLineId(), line.getLineNo(), line.getItemId(),
                        item == null ? line.getItemId() : item.itemCode(), item == null ? null : item.itemName(),
                        item == null ? null : item.unitCode(), item != null && "Y".equals(item.lotManageYn()), line.getQuantity(),
                        line.getReceivedQuantity(), line.remaining(), line.getExpectedDate(), line.getNote(), line.getUnitPrice(),
                        value(line), returnedByLine.getOrDefault(line.getLineId(), BigDecimal.ZERO), line.getSupplierItemCode(),
                        line.getReplacementQuantity());
                }).toList(),
                orderReceipts.stream()
                    .map(receipt -> new PurchaseOrderResponse.Receipt(receipt.getReceiptId(), receipt.getLineId(),
                        codes.getOrDefault(receipt.getLineId(), receipt.getLineId()), receipt.getQuantity(), receipt.getInventoryId(),
                        receipt.getLocation(), receipt.getLotNo(), receipt.getReceivedBy(), receipt.getReceivedAt()))
                    .toList(),
                orderLines.stream().map(PurchaseOrderService::value).filter(Objects::nonNull).reduce(NO_VALUE, BigDecimal::add),
                orderLines.stream().allMatch(line -> line.getUnitPrice() != null),
                // Moved together, the order's own date comes before its lines', which follow the line numbers.
                changesByOrder.getOrDefault(order.getPurchaseOrderId(), List.of()).stream()
                    .sorted(Comparator.comparing(PurchaseOrderDateChange::getChangedAt)
                        .thenComparing(change -> change.getLineId() == null ? 0 : lineNos.getOrDefault(change.getLineId(), Integer.MAX_VALUE))
                        .thenComparing(PurchaseOrderDateChange::getChangeId))
                    .map(change -> new PurchaseOrderResponse.DateChange(change.getChangeId(), change.getLineId(),
                        change.getLineId() == null ? null : codes.getOrDefault(change.getLineId(), change.getLineId()), change.getPreviousDate(),
                        change.getExpectedDate(), change.getReason(), change.getChangedBy(), change.getChangedAt()))
                    .toList(),
                returnsByOrder.getOrDefault(order.getPurchaseOrderId(), List.of()).stream()
                    .sorted(Comparator.comparing(PurchaseReturn::getReturnedAt).thenComparing(PurchaseReturn::getReturnId))
                    .map(one -> {
                        PurchaseCredit credit = creditByReturn.get(one.getReturnId());
                        List<PurchaseCreditChange> changed = changesByReturn.getOrDefault(one.getReturnId(), List.of());
                        PurchaseCreditChange last = changed.isEmpty() ? null : changed.get(changed.size() - 1);
                        // The latest change stands for the credit; withdrawn, nothing is credited (PO52).
                        boolean kept = credit != null && (last == null || last.getAmount() != null);
                        return new PurchaseOrderResponse.Return(one.getReturnId(), one.getLineId(),
                            codes.getOrDefault(one.getLineId(), one.getLineId()), one.getQuantity(), one.getInventoryId(), one.getLocation(),
                            one.getLotNo(), one.getReason(), one.getReturnedBy(), one.getReturnedAt(), one.getNonconformityId(), one.getNcrNo(),
                            "Y".equals(one.getReplacementYn()), !kept ? null : last == null ? credit.getAmount() : last.getAmount(),
                            !kept ? null : last == null ? credit.getCreditNote() : last.getCreditNote(),
                            !kept ? null : last == null ? credit.getCreditedBy() : last.getChangedBy(),
                            !kept ? null : last == null ? credit.getCreditedAt() : last.getChangedAt(),
                            changed.stream().map(change -> new PurchaseOrderResponse.CreditChange(change.getAmount(), change.getCreditNote(),
                                change.getReason(), change.getChangedBy(), change.getChangedAt())).toList());
                    })
                    .toList(),
                order.getApprovedBy(), order.getApprovedAt(), !needed.isEmpty(), order.getDeliverTo(),
                approvalSteps(needed, approvalsByOrder.getOrDefault(order.getPurchaseOrderId(), List.of())));
        }).toList();
    }

    private PurchaseOrder lock(String purchaseOrderId) {
        return orders.findForUpdate(purchaseOrderId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static String reason(PurchaseOrderEndRequest request) {
        String reason = text(request == null ? null : request.reason(), "reason", MAX_NOTE);
        if (reason == null) {
            throw bad("Give a reason.");
        }
        return reason;
    }

    private static String status(String value) {
        String status = value.trim().toLowerCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw bad("status is one of " + String.join(", ", STATUSES) + ".");
        }
        return status;
    }

    /** Tells the first approver about a saved draft that waits for them (PO36, PO50); nobody when they saved it. */
    private void askForApproval(PurchaseOrder order, List<PurchaseOrderLine> orderLines) {
        List<ApprovalRule> needed = approvalsNeeded(orderLines, settings.approvalRules(order.getProjectId()));
        if (needed.isEmpty()) {
            return;
        }
        long unpriced = orderLines.stream().filter(line -> line.getUnitPrice() == null).count();
        String notPriced = unpriced + (unpriced == 1 ? " line" : " lines") + " not priced yet";
        String worth = "worth " + orderLines.stream().map(PurchaseOrderService::value).filter(Objects::nonNull).reduce(NO_VALUE, BigDecimal::add)
            .stripTrailingZeros().toPlainString();
        String what = unpriced == orderLines.size() ? notPriced : unpriced == 0 ? worth : worth + ", " + notPriced;
        notifications.notifyAssignee(needed.get(0).approverId(), access.requireCurrentUserId(), order.getProjectId(),
            "purchase_approval", order.getOrderNumber() + " needs your approval", order.getSupplierName() + ": " + what + ".", "purchase_order",
            order.getPurchaseOrderId());
    }

    /**
     * The approvals a draft needs, in turn (PO34, PO50): every rule from whose value its worth is, or every rule while a line has
     * no price; each approver once.
     */
    private static List<ApprovalRule> approvalsNeeded(List<PurchaseOrderLine> orderLines, List<ApprovalRule> rules) {
        boolean unpriced = orderLines.stream().anyMatch(line -> line.getUnitPrice() == null);
        BigDecimal worth = orderLines.stream().map(PurchaseOrderService::value).filter(Objects::nonNull).reduce(NO_VALUE, BigDecimal::add);
        Set<String> seen = new HashSet<>();
        return rules.stream().filter(rule -> unpriced || worth.compareTo(rule.fromValue()) >= 0).filter(rule -> seen.add(rule.approverId()))
            .toList();
    }

    private static String approverName(ApprovalRule rule) {
        return rule.projectOwner() ? "the project owner" : rule.approverId();
    }

    /** A draft's approvals in turn, each with when it was given; another order's as they were given (PO50). */
    private static List<PurchaseOrderResponse.Approval> approvalSteps(List<ApprovalRule> needed, List<PurchaseOrderApproval> given) {
        if (needed.isEmpty()) {
            return given.stream().sorted(Comparator.comparing(PurchaseOrderApproval::getApprovedAt))
                .map(one -> new PurchaseOrderResponse.Approval(one.getApproverId(), null, false, one.getApprovedAt())).toList();
        }
        Map<String, OffsetDateTime> at = given.stream()
            .collect(Collectors.toMap(PurchaseOrderApproval::getApproverId, PurchaseOrderApproval::getApprovedAt));
        return needed.stream()
            .map(rule -> new PurchaseOrderResponse.Approval(rule.approverId(), rule.fromValue(), rule.projectOwner(), at.get(rule.approverId())))
            .toList();
    }

    /** Quantity × unit price, four decimals; null without a price (PO14). */
    private static BigDecimal value(PurchaseOrderLine line) {
        return line.getUnitPrice() == null ? null : line.getQuantity().multiply(line.getUnitPrice()).setScale(4, RoundingMode.HALF_UP);
    }

    /** Empty while not agreed; else 0 or more, at most four decimals and ten whole digits, as item unit costs are kept. */
    private static BigDecimal price(BigDecimal value, String what) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw bad(what + " cannot be below 0.");
        }
        if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
            throw bad(what + " has more than four decimals.");
        }
        if (CsvDecimalParser.hasMoreThanIntegerDigits(value, MAX_INTEGER_DIGITS)) {
            throw bad(what + " is too large.");
        }
        return value.setScale(4);
    }

    /** 0 or more, at most four decimals and twenty whole digits (PO47). */
    private static BigDecimal amount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw bad("amount must be 0 or more.");
        }
        if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
            throw bad("amount has more than four decimals.");
        }
        if (CsvDecimalParser.hasMoreThanIntegerDigits(value, MAX_AMOUNT_DIGITS)) {
            throw bad("amount is too large.");
        }
        return value.setScale(4);
    }

    /** Above 0, at most four decimals and ten whole digits, as stock quantities are kept. */
    private static BigDecimal quantity(BigDecimal value, String what) {
        if (value == null || value.signum() <= 0) {
            throw bad(what + " must be greater than 0.");
        }
        if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
            throw bad(what + " has more than four decimals.");
        }
        if (CsvDecimalParser.hasMoreThanIntegerDigits(value, MAX_INTEGER_DIGITS)) {
            throw bad(what + " is too large.");
        }
        return value.setScale(4);
    }

    private static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException exception) {
            throw bad(field + " is a date like 2041-09-10.");
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw bad(field + " is required.");
        }
        return value.trim();
    }

    private static String text(String value, String field, int max) {
        StorableText.require(value, field);
        String trimmed = value == null || value.isBlank() ? null : value.trim();
        if (trimmed != null && trimmed.length() > max) {
            throw bad(field + " must be at most " + max + " characters.");
        }
        return trimmed;
    }

    private static BusinessException conflict(PurchaseOrder order, String rule) {
        return new BusinessException(ErrorCode.CONFLICT, order.getOrderNumber() + " is " + order.getStatus() + "; " + rule + ".");
    }

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
