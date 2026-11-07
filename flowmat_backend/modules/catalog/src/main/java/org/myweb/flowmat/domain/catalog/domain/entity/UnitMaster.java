package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.myweb.flowmat.global.common.BaseTimeEntity;

@Getter
@Setter
@Entity
@Table(name = "unit_master")
public class UnitMaster extends BaseTimeEntity {

    @Id
    private String unitId;

    private String unitCode;
    private String unitName;
    private String unitType;

    /** Null for a base unit; otherwise the code of the base unit this one converts to. */
    private String baseUnitCode;

    /** Multiply a quantity in this unit by the rate to get the base unit (1 g = 0.001 kg). */
    @Column(precision = 18, scale = 8)
    private BigDecimal conversionRate;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 1)
    private String activeYn;
}
