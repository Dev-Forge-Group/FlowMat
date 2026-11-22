package org.myweb.flowmat.domain.purchasing.application.publicapi;

import java.math.BigDecimal;
import java.util.List;

/**
 * Drafts a purchase order for other bounded contexts, as a person would on the purchasing screen (docs/domain/purchasing.md
 * PO1-PO2): numbered in the project, checked the same way, needing project write access. A plan's confirmation drafts its
 * shortages with it (ADR-043 decision 7).
 */
public interface PurchaseDraftCommand {

    record Line(String itemId, BigDecimal quantity) {
    }

    /** The new draft's id. */
    String draft(String projectId, String supplierName, String note, List<Line> lines);
}
