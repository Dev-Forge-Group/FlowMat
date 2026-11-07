package org.myweb.flowmat.domain.bom.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.BaseTimeEntity;

/**
 * One material of a BOM revision. scrapRate (0 to 1, material lines only) adds planned scrap on top of the quantity
 * (ADR-031). Material lines sharing a substituteGroup stand in for one another; the one with the lowest priority is the
 * main material and the plan uses only it (ADR-032). An optional material ("Y") is planned only for a work order that
 * picks its item (ADR-033).
 */
@Getter
@Setter
@Entity
@Table(name = "bom_line")
public class BomLine extends BaseTimeEntity {

    @Id
    private String bomLineId;

    private String bomId;
    private String childItemId;
    private String lineType;

    /**
     * "Y": the material is a phantom sub-assembly, replaced by its own BOM's materials wherever this BOM is used
     * (docs/domain/multi-level-bom.md P1-P2). "N" unless set, like the column.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String phantomYn = "N";
    private BigDecimal quantity;
    private String unit;
    private BigDecimal scrapRate;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String optionalYn;
    private String substituteGroup;
    /** Order of preference inside the substitute group, lowest first; 0 unless set, like the column. */
    private Integer priority = 0;
    private Integer sortOrder;
    private String note;
    private String createdBy;
}
