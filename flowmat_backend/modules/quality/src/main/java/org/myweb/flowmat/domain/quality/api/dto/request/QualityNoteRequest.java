package org.myweb.flowmat.domain.quality.api.dto.request;

import jakarta.validation.constraints.Size;

/** What was done, or why: finishing or cancelling an action, closing or cancelling a nonconformity. */
public record QualityNoteRequest(@Size(max = 1000) String note) {
}
