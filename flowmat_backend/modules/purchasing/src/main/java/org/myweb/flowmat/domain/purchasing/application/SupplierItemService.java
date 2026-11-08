package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogItemView;
import org.myweb.flowmat.domain.catalog.application.publicapi.CatalogQuery;
import org.myweb.flowmat.domain.project.application.publicapi.ProjectAccess;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierItemRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.SupplierItemResponse;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.myweb.flowmat.domain.purchasing.domain.entity.SupplierItem;
import org.myweb.flowmat.domain.purchasing.repository.SupplierItemRepository;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.CsvDecimalParser;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The supplier each item is usually bought from (docs/architecture/adr/ADR-027-purchasing.md decision 13, docs/domain/purchasing.md
 * PO29-PO31). Drafts of purchase orders go to it; an order still names whichever supplier it is written for.
 */
@Service
@RequiredArgsConstructor
public class SupplierItemService {
    private static final int MAX_CODE = 100;
    private static final int MAX_INTEGER_DIGITS = 10;

    private final SupplierItemRepository supplierItems;
    private final SupplierRepository suppliers;
    private final CatalogQuery catalog;
    private final ProjectAccess access;
    private final IdGenerator ids;

    /** By item code. */
    @Transactional(readOnly = true)
    public List<SupplierItemResponse> list(String projectId) {
        String project = required(projectId, "projectId");
        access.requireProjectReadAccess(project);
        List<SupplierItem> found = supplierItems.findAllByProjectId(project);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<String, CatalogItemView> items = catalog.findItems(found.stream().map(SupplierItem::getItemId).toList());
        Map<String, Supplier> byId = suppliers.findAllById(found.stream().map(SupplierItem::getSupplierId).collect(Collectors.toSet())).stream()
            .collect(Collectors.toMap(Supplier::getSupplierId, Function.identity()));
        return found.stream()
            .map(one -> response(one, items.get(one.getItemId()), byId.get(one.getSupplierId())))
            .sorted(Comparator.comparing(SupplierItemResponse::itemCode).thenComparing(SupplierItemResponse::itemId))
            .toList();
    }

    /**
     * Sets an item's usual supplier, an active one of the project, from the version the editor loaded; an empty supplier
     * clears it and answers null (PO29). Sending what is already set answers it as it is, so a repeat after a lost answer
     * is harmless.
     */
    @Transactional
    public SupplierItemResponse set(SupplierItemRequest request) {
        if (request == null) {
            throw bad("Send the item and its usual supplier.");
        }
        String projectId = required(request.projectId(), "projectId");
        access.requireProjectWriteAccess(projectId);
        String itemId = required(request.itemId(), "itemId");
        CatalogItemView item = catalog.findItems(List.of(itemId)).get(itemId);
        if (item == null || !projectId.equals(item.projectId())) {
            throw bad("Choose an item of this project.");
        }
        String supplierId = request.supplierId() == null || request.supplierId().isBlank() ? null : request.supplierId().trim();
        String code = text(request.supplierItemCode(), "supplierItemCode", MAX_CODE);
        BigDecimal price = price(request.unitPrice());
        suppliers.lockKey("supplier-item|" + projectId + "|" + itemId);
        Optional<SupplierItem> current = supplierItems.findByProjectIdAndItemId(projectId, itemId);
        if (supplierId == null && current.isEmpty()) {
            return null;
        }
        if (current.isPresent() && current.get().getSupplierId().equals(supplierId) && Objects.equals(current.get().getSupplierItemCode(), code)
            && same(current.get().getUnitPrice(), price)) {
            return response(current.get(), item, suppliers.findById(supplierId).orElse(null));
        }
        Long seen = current.map(SupplierItem::getVersion).orElse(null);
        if (!Objects.equals(seen, request.expectedVersion())) {
            throw new BusinessException(ErrorCode.CONFLICT, item.itemCode() + "'s usual supplier changed since it was opened; reload it and try again.");
        }
        if (supplierId == null) {
            supplierItems.delete(current.get());
            supplierItems.flush();
            return null;
        }
        Supplier supplier = suppliers.findById(supplierId).filter(one -> projectId.equals(one.getProjectId()))
            .orElseThrow(() -> bad("Choose a supplier of this project."));
        if (!supplier.isActive()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Supplier " + supplier.getSupplierName() + " is inactive.");
        }
        SupplierItem saved = current.orElseGet(() -> {
            SupplierItem created = new SupplierItem();
            created.setSupplierItemId(ids.generate());
            created.setProjectId(projectId);
            created.setItemId(itemId);
            created.setVersion(-1);
            return created;
        });
        saved.setSupplierId(supplierId);
        saved.setSupplierItemCode(code);
        saved.setUnitPrice(price);
        saved.setVersion(saved.getVersion() + 1);
        saved.setUpdatedBy(access.requireCurrentUserId());
        saved.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return response(supplierItems.saveAndFlush(saved), item, supplier);
    }

    private static SupplierItemResponse response(SupplierItem one, CatalogItemView item, Supplier supplier) {
        return new SupplierItemResponse(one.getSupplierItemId(), one.getProjectId(), one.getItemId(),
            item == null ? one.getItemId() : item.itemCode(), one.getSupplierId(), supplier == null ? one.getSupplierId() : supplier.getSupplierName(),
            supplier != null && supplier.isActive(), one.getSupplierItemCode(), one.getUnitPrice(), one.getVersion(), one.getUpdatedBy(), one.getUpdatedAt());
    }

    /** Empty while not agreed; else 0 or more, at most four decimals and ten whole digits, as order prices are kept (PO38). */
    private static BigDecimal price(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw bad("unitPrice cannot be below 0.");
        }
        if (value.scale() > 4 && value.stripTrailingZeros().scale() > 4) {
            throw bad("unitPrice has more than four decimals.");
        }
        if (CsvDecimalParser.hasMoreThanIntegerDigits(value, MAX_INTEGER_DIGITS)) {
            throw bad("unitPrice is too large.");
        }
        return value.setScale(4);
    }

    private static boolean same(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
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

    private static BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }
}
