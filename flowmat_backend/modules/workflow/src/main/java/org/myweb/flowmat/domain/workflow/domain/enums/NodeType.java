package org.myweb.flowmat.domain.workflow.domain.enums;

import java.util.Arrays;
import java.util.Locale;

public enum NodeType {
    PROCESS,
    EQUIPMENT,
    STORAGE,
    INPUT,
    OUTPUT,
    /** A gateway that does no work: the server passes it at once to one of its connections (ADR-006). */
    EXCLUSIVE,
    /** A gateway that waits for the branches that can still reach it, then the server passes it on once (ADR-007). */
    JOIN,
    /** A gateway that divides the quantity that arrives between its connections; the server passes it at once (ADR-008). */
    SPLIT;

    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return PROCESS.name().toLowerCase(Locale.ROOT);
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);

        if ("DECISION".equals(normalized)) {
            return OUTPUT.name().toLowerCase(Locale.ROOT);
        }

        return Arrays.stream(values())
            .filter(nodeType -> nodeType.name().equals(normalized))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unsupported nodeType: " + value))
            .name()
            .toLowerCase(Locale.ROOT);
    }
}
