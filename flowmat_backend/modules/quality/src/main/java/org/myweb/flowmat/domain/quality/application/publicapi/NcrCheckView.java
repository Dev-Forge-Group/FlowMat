package org.myweb.flowmat.domain.quality.application.publicapi;

import java.time.LocalDate;

/** A closed nonconformity whose effectiveness check is still to be recorded, by its due day (notification NS7). */
public record NcrCheckView(String nonconformityId, String projectId, String ncrNo, String title, LocalDate verificationDueDate) {
}
