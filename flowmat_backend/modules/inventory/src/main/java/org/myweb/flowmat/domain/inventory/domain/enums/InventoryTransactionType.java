package org.myweb.flowmat.domain.inventory.domain.enums;

import java.util.Arrays;
import java.util.Optional;

/**
 * Every stock movement type. See docs/domain/inventory-bom-lot-contract.md §2.
 *
 * <p>{@code quantitySign} / {@code reservedSign} give the direction of a positive requested quantity; adjustment and
 * reversal carry their own direction.
 */
public enum InventoryTransactionType {
    RECEIPT("receipt", 1, 0, true, false, true),
    ISSUE("issue", -1, 0, true, true, true),
    // Not reversible here: the run's items and LOT genealogy would no longer match the stock. Correct them on the run.
    PRODUCTION_INPUT("production_input", -1, 0, false, true, false),
    PRODUCTION_OUTPUT("production_output", 1, 0, false, false, false),
    RESERVE("reserve", 0, 1, true, true, true),
    RELEASE("release", 0, -1, true, false, true),
    ADJUSTMENT("adjustment", 0, 0, true, false, true),
    REVERSAL("reversal", 0, 0, false, false, false),
    QUARANTINE("quarantine", 0, 0, true, false, false),
    UNQUARANTINE("unquarantine", 0, 0, true, false, false),
    // The two legs of POST /inventory-transfers, written together; a transfer is undone by a transfer back.
    TRANSFER_OUT("transfer_out", -1, 0, false, true, false),
    TRANSFER_IN("transfer_in", 1, 0, false, false, false);

    private final String code;
    private final int quantitySign;
    private final int reservedSign;
    private final boolean external;
    private final boolean blockedByQuarantine;
    private final boolean reversible;

    InventoryTransactionType(
        String code,
        int quantitySign,
        int reservedSign,
        boolean external,
        boolean blockedByQuarantine,
        boolean reversible
    ) {
        this.code = code;
        this.quantitySign = quantitySign;
        this.reservedSign = reservedSign;
        this.external = external;
        this.blockedByQuarantine = blockedByQuarantine;
        this.reversible = reversible;
    }

    public String code() {
        return code;
    }

    public int quantitySign() {
        return quantitySign;
    }

    public int reservedSign() {
        return reservedSign;
    }

    /** Allowed on POST /inventory-transactions; the rest are written by production runs or the reversal endpoint. */
    public boolean external() {
        return external;
    }

    /** Takes stock out of use, so it is refused while the stock is quarantined. */
    public boolean blockedByQuarantine() {
        return blockedByQuarantine;
    }

    public boolean reversible() {
        return reversible;
    }

    public boolean changesStatusOnly() {
        return this == QUARANTINE || this == UNQUARANTINE;
    }

    public static Optional<InventoryTransactionType> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String normalized = code.trim().toLowerCase();
        return Arrays.stream(values()).filter(type -> type.code.equals(normalized)).findFirst();
    }
}
