package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * Closing a nonconformity (docs/domain/nonconformity.md N8, N13).
 *
 * @param note                how it was verified; optional
 * @param verificationDueDate the day its actions are due to be checked; optional, not before the project's today
 */
public record NonconformityCloseRequest(@Size(max = 1000) String note, LocalDate verificationDueDate) {
}
