package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "production_run_item")
public class ProductionRunItem {

    @Id
    private String productionRunItemId;

    private String productionRunId;
    private String processId;
    private String processIoId;
    private String inventoryId;
    private String itemId;
    private String lotId;
    private String direction;
    private BigDecimal plannedQty;
    private BigDecimal actualQty;
    @Column(length = 20)
    private String unit;

    /** "manual" when recorded by hand, "bom" when planned from a BOM snapshot, "correction" when added by a correction. */
    private String quantitySource;

    /** The correction that added this recording (docs/domain/production-run-correction.md). */
    private String productionRunCorrectionId;

    /** The correction that voided this recording; null when it was cancelled on the open run (or not at all). */
    private String cancelledByCorrectionId;

    /** BOM snapshot: factor from the BOM line unit to the item unit, fixed when the run starts. */
    private BigDecimal conversionRate;

    /** BOM snapshot: the phantom sub-assembly this planned line was expanded through; null otherwise (multi-level-bom.md P2). */
    private String viaItemId;

    /** "Y" once the recording was cancelled: its stock movement is reversed and it no longer counts. */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String cancelledYn = "N";
    private String cancelledBy;
    private OffsetDateTime cancelledAt;
    private String cancelReason;

    public boolean isCancelled() {
        return "Y".equals(cancelledYn);
    }
}
