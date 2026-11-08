package org.myweb.flowmat.domain.inventory.domain.enums;

import java.util.Arrays;

/** LOT lifecycle (docs/domain/inventory-bom-lot-contract.md §6). Stored lower-case in lot_master.lot_status. */
public enum LotStatus {
    AVAILABLE("available"),
    RESERVED("reserved"),
    QUARANTINED("quarantined"),
    /** Waiting for its receipt checks: stock can be received but stays quarantined until quality releases the LOT. */
    INSPECTION_PENDING("inspection_pending"),
    CONSUMED("consumed"),
    CLOSED("closed");

    private final String code;

    LotStatus(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Stock of the LOT can be issued, consumed or reserved. */
    public boolean usable() {
        return this == AVAILABLE || this == RESERVED;
    }

    public static LotStatus fromCode(String code) {
        return Arrays.stream(values())
            .filter(status -> status.code.equalsIgnoreCase(code == null ? "" : code.trim()))
            .findFirst()
            .orElse(AVAILABLE);
    }
}
