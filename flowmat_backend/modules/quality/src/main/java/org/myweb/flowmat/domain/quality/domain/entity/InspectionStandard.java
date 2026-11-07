package org.myweb.flowmat.domain.quality.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

/** A check an item gets, with its limits (docs/domain/inspection-standard.md). */
@Getter
@Setter
@Entity
@Table(name = "inspection_standard")
public class InspectionStandard extends CreatedUpdatedAuditEntity {

    @Id
    private String standardId;

    private String projectId;
    private String itemId;

    /** The check's name, as inspections record it in inspection_type, e.g. "Moisture". */
    private String inspectionType;

    /** receipt, production or any: when the check applies. */
    private String stage;

    @Column(precision = 14, scale = 4)
    private BigDecimal standardMin;

    @Column(precision = 14, scale = 4)
    private BigDecimal standardMax;

    private String unit;

    /** Y: every production run of the item should record this check (see the run's quality checklist). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requiredYn;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String activeYn;

    private String note;
}
