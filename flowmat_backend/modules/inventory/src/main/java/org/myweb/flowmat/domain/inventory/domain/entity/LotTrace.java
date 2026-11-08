package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** Genealogy edge: the parent LOT was consumed by a run that produced the child LOT. */
@Getter
@Setter
@Entity
@Table(name = "lot_trace")
public class LotTrace {

    @Id
    private String lotTraceId;

    private String projectId;
    private String parentLotId;
    private String childLotId;
    private String productionRunId;
    private String processId;
    private BigDecimal consumedQty;
    private BigDecimal producedQty;
    private String unit;
    private OffsetDateTime createdAt;
}
