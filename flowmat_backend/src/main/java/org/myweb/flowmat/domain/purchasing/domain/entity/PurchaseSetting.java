package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** A project's purchasing settings (docs/architecture/adr/ADR-027-purchasing.md decision 14, V113). */
@Getter
@Setter
@Entity
@Table(name = "purchase_setting")
public class PurchaseSetting {
    @Id
    private String projectId;
    /** Orders worth this or more need the owner's approval; null when none does. */
    private BigDecimal approvalLimit;
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
