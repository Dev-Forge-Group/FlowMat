package org.myweb.flowmat.domain.bom.api.dto.response;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.myweb.flowmat.domain.bom.domain.entity.BomEffectivityChange;

public record BomEffectivityResponse(String bomId, String bomStatus, LocalDate effectiveFrom, LocalDate effectiveTo,
    Long periodVersion, List<Change> history) {
    public record Change(String changeId, LocalDate previousEffectiveFrom, LocalDate previousEffectiveTo,
        LocalDate effectiveFrom, LocalDate effectiveTo, Long periodVersion, String reason, String changedBy,
        OffsetDateTime changedAt) {
        public static Change from(BomEffectivityChange value) {
            return new Change(value.getChangeId(),value.getPreviousEffectiveFrom(),value.getPreviousEffectiveTo(),
                value.getEffectiveFrom(),value.getEffectiveTo(),value.getPeriodVersion(),value.getReason(),
                value.getChangedBy(),value.getChangedAt());
        }
    }
}
