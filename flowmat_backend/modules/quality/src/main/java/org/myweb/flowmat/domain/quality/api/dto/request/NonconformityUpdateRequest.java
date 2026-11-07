package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Changes an open nonconformity; only the fields given change, and an empty description or root cause clears it.
 *
 * @param disposition pending, use_as_is, rework, scrap or return_to_supplier
 */
public record NonconformityUpdateRequest(
    @Size(max = 200) String title,
    @Size(max = 2000) String description,
    String severity,
    @Size(max = 2000) String rootCause,
    String disposition
) {
}
