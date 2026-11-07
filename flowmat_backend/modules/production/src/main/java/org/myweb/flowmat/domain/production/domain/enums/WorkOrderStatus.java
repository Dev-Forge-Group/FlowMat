package org.myweb.flowmat.domain.production.domain.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Work order lifecycle. Stored lowercase in {@code work_order.work_order_status}.
 *
 * <pre>
 * draft ──approve──▶ approved ──first run──▶ in_progress ──complete──▶ completed
 *   └──────cancel──────┴──────cancel──▶ cancelled
 * </pre>
 */
public enum WorkOrderStatus {
    DRAFT,
    APPROVED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static WorkOrderStatus fromCode(String code) {
        String normalized = code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(status -> status.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown work order status: " + code));
    }

    public boolean canTransitionTo(WorkOrderStatus next) {
        return switch (this) {
            case DRAFT -> Set.of(APPROVED, CANCELLED).contains(next);
            case APPROVED -> Set.of(IN_PROGRESS, CANCELLED).contains(next);
            case IN_PROGRESS -> next == COMPLETED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /** Production runs may only be started against an approved or already running order. */
    public boolean acceptsRuns() {
        return this == APPROVED || this == IN_PROGRESS;
    }
}
