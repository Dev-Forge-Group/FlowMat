package org.myweb.flowmat.domain.workflow.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** What to make, in a person's own words (ADR-047). Only this text is sent to the model. */
public record ProcessDraftRequest(@NotBlank @Size(max = 2000) String description) {
}
