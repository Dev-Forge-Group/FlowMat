package org.myweb.flowmat.domain.workflow.domain.contract;

import java.util.Locale;
import java.util.Set;

/**
 * The resource type registry: what may flow through a port (docs/architecture/adr/ADR-003-resource-port-contract.md,
 * "Resource Type Registry", status Experimental). A port may still store another value: workflow validation warns about it
 * ({@code RESOURCE_TYPE_UNKNOWN}) instead of rejecting it, so saved graphs and published revisions keep working. What a
 * process needs to run rather than consumes through a port (labor, equipment, compute, skill, time) is an execution
 * requirement, not a port resource, so {@code labor} is not here even though the port editor offers it.
 */
public final class ResourceTypes {

    public static final Set<String> STANDARD = Set.of(
        "material", "product", "energy", "water", "waste",
        "file", "data", "api", "parameter", "signal",
        "generic");

    private ResourceTypes() {
    }

    /** Whether a stored resource type is standard, ignoring case and surrounding spaces; blank means nothing to warn about. */
    public static boolean isStandard(String resourceType) {
        return resourceType == null || resourceType.isBlank()
            || STANDARD.contains(resourceType.trim().toLowerCase(Locale.ROOT));
    }
}
