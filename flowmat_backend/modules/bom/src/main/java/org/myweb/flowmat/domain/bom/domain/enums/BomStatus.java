package org.myweb.flowmat.domain.bom.domain.enums;

import java.util.Arrays;

/** BOM revision lifecycle (docs/domain/inventory-bom-lot-contract.md §5). Stored lower-case in bom_header.bom_status. */
public enum BomStatus {
    DRAFT("draft"),
    PENDING_APPROVAL("pending_approval"),
    APPROVED("approved"),
    RETIRED("retired");

    private final String code;

    BomStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Only drafts can be edited; everything after submit is fixed. */
    public boolean editable() {
        return this == DRAFT;
    }

    public boolean canTransitionTo(BomStatus next) {
        return switch (this) {
            case DRAFT -> next == PENDING_APPROVAL;
            case PENDING_APPROVAL -> next == APPROVED || next == DRAFT;
            case APPROVED -> next == RETIRED;
            case RETIRED -> false;
        };
    }

    public static BomStatus fromCode(String code) {
        return Arrays.stream(values())
            .filter(status -> status.code.equalsIgnoreCase(code == null ? "" : code.trim()))
            .findFirst()
            .orElse(DRAFT);
    }
}
