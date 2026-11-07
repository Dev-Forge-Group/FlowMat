package org.myweb.flowmat.domain.bom.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "bom_effectivity_change")
public class BomEffectivityChange {
    @Id private String changeId;
    private String bomId;
    private UUID requestId;
    private LocalDate previousEffectiveFrom;
    private LocalDate previousEffectiveTo;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long periodVersion;
    @Column(length = 1000) private String reason;
    private String changedBy;
    private OffsetDateTime changedAt;
}
