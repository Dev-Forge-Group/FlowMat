package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * @param actionType correction (fix this case), corrective (remove the cause) or preventive (keep it from happening
 *                   elsewhere)
 * @param ownerId a member of the project, or empty
 */
public record CorrectiveActionRequest(
    @NotBlank String actionType,
    @NotBlank @Size(max = 1000) String description,
    String ownerId,
    LocalDate dueDate
) {
}
