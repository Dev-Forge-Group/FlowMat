package org.myweb.flowmat.domain.purchasing.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Someone a project buys from (docs/architecture/adr/ADR-027-purchasing.md decision 10, V109). Switched off, never deleted;
 * purchase orders keep the name they were given.
 */
@Getter
@Setter
@Entity
@Table(name = "supplier")
public class Supplier {
    @Id
    private String supplierId;
    private String projectId;
    /** Unique in the project, ignoring case. */
    @Column(length = 100)
    private String supplierName;
    /** A person, phone or e-mail, as typed. */
    @Column(length = 200)
    private String contact;
    /** Days from ordering to arrival, 0-365; null when not known. */
    private Integer leadTimeDays;
    @Column(length = 500)
    private String note;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;
    /** One up with every change; an edit names the version it started from. */
    private long version;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String updatedBy;
    private OffsetDateTime updatedAt;

    public boolean isActive() {
        return "Y".equals(activeYn);
    }
}
