package org.myweb.flowmat.domain.quality.api.dto.request;

import java.time.LocalDate;

/**
 * The day a closed nonconformity's actions are due to be checked (docs/domain/nonconformity.md N13).
 *
 * @param verificationDueDate not before the project's today; null clears it
 */
public record NonconformityVerificationDueRequest(LocalDate verificationDueDate) {
}
