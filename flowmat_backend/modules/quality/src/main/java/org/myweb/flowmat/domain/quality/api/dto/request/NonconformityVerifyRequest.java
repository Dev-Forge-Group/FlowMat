package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Whether a closed nonconformity's actions worked (docs/domain/nonconformity.md N12).
 *
 * @param result {@code effective} or {@code not_effective}
 * @param note   what was checked; required when the actions did not work
 */
public record NonconformityVerifyRequest(@Size(max = 20) String result, @Size(max = 1000) String note) {
}
