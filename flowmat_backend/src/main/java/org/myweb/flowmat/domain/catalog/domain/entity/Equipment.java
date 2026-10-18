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
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "equipment")
public class Equipment extends CreatedUpdatedAuditEntity {

    @Id
    private String equipmentId;

    private String projectId;
    private String equipmentCode;
    private String equipmentName;
    private String equipmentType;
    private String equipmentStatus;

    private String manufacturer;
    private String modelName;
    private String serialNo;

    @Column(precision = 14, scale = 4)
    private BigDecimal capacityPerHour;

    @Column(precision = 10, scale = 4)
    private BigDecimal powerKwh;

    @Column(precision = 14, scale = 4)
    private BigDecimal waterLiter;

    /** The share of the rated output per hour it really reaches, above 0 to 100; null is 100 (V84). */
    @Column(precision = 5, scale = 2)
    private BigDecimal efficiencyPercent;

    /** Y when several work orders may run on it at once, so plans do not keep them apart (V86, E12). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String concurrentWorkOrdersYn = "N";

    /** At most this many at once when concurrentWorkOrdersYn is Y, 2 to 99; null is no limit (V90, E16). */
    private Integer concurrentWorkOrdersLimit;

    private String location;
}
