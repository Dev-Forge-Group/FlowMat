package org.myweb.flowmat.domain.inventory.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Holds every LOT a suspect LOT went into; the reason is written on each quarantine movement. */
public record LotRecallQuarantineRequest(@NotBlank @Size(max = 300) String reason) {
}
