package org.myweb.flowmat.domain.purchasing.application;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.myweb.flowmat.domain.project.application.ProjectAccessService;
import org.myweb.flowmat.domain.purchasing.api.dto.request.SupplierRequest;
import org.myweb.flowmat.domain.purchasing.api.dto.response.SupplierResponse;
import org.myweb.flowmat.domain.purchasing.domain.entity.Supplier;
import org.myweb.flowmat.domain.purchasing.repository.SupplierRepository;
import org.myweb.flowmat.global.exception.BusinessException;
import org.myweb.flowmat.global.exception.ErrorCode;
import org.myweb.flowmat.global.id.IdGenerator;
import org.myweb.flowmat.global.util.StorableText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The suppliers a project buys from (docs/architecture/adr/ADR-027-purchasing.md decision 10, docs/domain/purchasing.md
 * PO18-PO21). Without any, a purchase order names its supplier freely; once the project lists one, orders name an active
 * supplier as the list spells it, as new stock follows the location list.
 */
@Service
@RequiredArgsConstructor
public class SupplierService {
    private static final String KEY = "supplier|";
    private static final int MAX_NAME = 100;
    private static final int MAX_CONTACT = 200;
    private static final int MAX_NOTE = 500;
    private static final int MAX_LEAD_DAYS = 365;

    private final SupplierRepository suppliers;
    private final ProjectAccessService access;
    private final IdGenerator ids;

    /** By name, ignoring case; switched-off suppliers too. */
    @Transactional(readOnly = true)
    public List<SupplierResponse> list(String projectId) {
        String project = required(projectId, "projectId");
        access.requireProjectReadAccess(project);
        return suppliers.findAllByProjectId(project).stream()
            .sorted(Comparator.comparing((Supplier one) -> one.getSupplierName().toLowerCase(Locale.ROOT)).thenComparing(Supplier::getSupplierId))
            .map(SupplierService::response)
            .toList();
    }

    /** A new, active supplier with a name of its own in the project (PO18). */
    @Transactional
    public SupplierResponse create(SupplierRequest request) {
        if (request == null) {
            throw bad("Send the supplier.");
        }
        String projectId = required(request.projectId(), "projectId");
        access.requireProjectWriteAccess(projectId);
        String name = name(request);
        Supplier supplier = new Supplier();
        supplier.setSupplierId(ids.generate());
        supplier.setProjectId(projectId);
        fill(supplier, name, request);
        supplier.setActiveYn("Y");
        supplier.setVersion(0);
        suppliers.lockKey(KEY + projectId);
        requireFreeName(projectId, name, null);
        supplier.setCreatedBy(access.requireCurrentUserId());
        supplier.setCreatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return response(suppliers.saveAndFlush(supplier));
    }

    /** Changes a supplier whole, from the version the editor loaded; purchase orders keep the name they were given (PO19). */
    @Transactional
    public SupplierResponse update(String supplierId, SupplierRequest request) {
        Supplier supplier = suppliers.findForUpdate(supplierId).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        access.requireProjectWriteAccess(supplier.getProjectId());
        if (request == null) {
            throw bad("Send the supplier.");
        }
        if (request.expectedVersion() == null) {
            throw bad("expectedVersion is required.");
        }
        String name = name(request);
        if (supplier.getVersion() != request.expectedVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT, supplier.getSupplierName() + " changed since it was opened; reload it and try again.");
        }
        suppliers.lockKey(KEY + supplier.getProjectId());
        // Before the row changes, so the check's query does not flush a clashing name first.
        requireFreeName(supplier.getProjectId(), name, supplier.getSupplierId());
        fill(supplier, name, request);
        if (request.active() != null) {
            supplier.setActiveYn(request.active() ? "Y" : "N");
        }
        supplier.setVersion(supplier.getVersion() + 1);
        supplier.setUpdatedBy(access.requireCurrentUserId());
        supplier.setUpdatedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS));
        return response(suppliers.saveAndFlush(supplier));
    }

    /**
     * The supplier name a purchase order is to carry (PO20): as typed while the project lists no supplier; else the listed
     * name as the list spells it. A name missing from the list is refused, and so is an inactive supplier.
     */
    public String resolve(String projectId, String name) {
        if (!suppliers.existsByProjectId(projectId)) {
            return name;
        }
        Supplier found = suppliers.findByName(projectId, name).orElseThrow(() -> bad("Supplier " + name
            + " is not in this project's supplier list. Add it under Inventory > Purchasing > Suppliers first."));
        if (!found.isActive()) {
            throw new BusinessException(ErrorCode.CONFLICT, "Supplier " + found.getSupplierName() + " is inactive.");
        }
        return found.getSupplierName();
    }

    private void requireFreeName(String projectId, String name, String supplierId) {
        suppliers.findByName(projectId, name)
            .filter(other -> !other.getSupplierId().equals(supplierId))
            .ifPresent(other -> {
                throw new BusinessException(ErrorCode.CONFLICT, other.getSupplierName() + " is already a supplier of this project.");
            });
    }

    private static String name(SupplierRequest request) {
        String name = text(request.supplierName(), "supplierName", MAX_NAME);
        if (name == null) {
            throw bad("Name the supplier.");
        }
        return name;
    }

    private static void fill(Supplier supplier, String name, SupplierRequest request) {
        supplier.setSupplierName(name);
        supplier.setContact(text(request.contact(), "contact", MAX_CONTACT));
        supplier.setLeadTimeDays(leadTime(request.leadTimeDays()));
        supplier.setNote(text(request.note(), "note", MAX_NOTE));
    }

    private static Integer leadTime(BigDecimal days) {
        if (days == null) {
            return null;
        }
        if (days.signum() < 0 || days.compareTo(BigDecimal.valueOf(MAX_LEAD_DAYS)) > 0 || days.stripTrailingZeros().scale() > 0) {
            throw bad("leadTimeDays is a whole number of days from 0 to " + MAX_LEAD_DAYS + ".");
        }
        return days.intValueExact();
    }

    private static SupplierResponse response(Supplier supplier) {
        return new SupplierResponse(supplier.getSupplierId(), supplier.getProjectId(), supplier.getSupplierName(), supplier.getContact(),
            supplier.getLeadTimeDays(), supplier.getNote(), supplier.isActive(), supplier.getVersion(), supplier.getCreatedBy(),
            supplier.getCreatedAt(), supplier.getUpdatedBy(), supplier.getUpdatedAt());
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
