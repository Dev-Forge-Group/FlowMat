package org.myweb.flowmat.domain.bom.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import org.hibernate.annotations.DynamicUpdate;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** One BOM revision. A revision is never changed once submitted; a change is a new row with the next bomVersion. */
@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "bom_header")
public class BomHeader extends CreatedUpdatedAuditEntity {

    @Id
    private String bomId;

    private String projectId;
    private String targetItemId;
    private String bomName;
    private Integer bomVersion;

    /** Output the lines produce, in {@link #baseUnit}. Requirements scale from this. */
    private BigDecimal baseQuantity;
    private String baseUnit;

    /** {@link org.myweb.flowmat.domain.bom.domain.enums.BomStatus} code. */
    private String bomStatus;

    /** Mirrors the approval step for reporting: draft, pending, approved, rejected. */
    private String approvalStatus;

    private String approvedBy;
    private OffsetDateTime approvedAt;
    /** Who submitted this revision for approval and when (V78); the owner's decision tells them (notification NT8). */
    private String submittedBy;
    private OffsetDateTime submittedAt;
    private String note;

    /** Inclusive dates; a missing boundary is unbounded. Managed through the explicit effectivity command. */
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private Long effectivePeriodVersion = 0L;
}
