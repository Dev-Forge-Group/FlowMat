package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** One change of an item's unit cost (docs/domain/material-cost.md "단가 이력"); 0 or none is an unknown cost. */
@Getter
@Setter
@Entity
@Table(name = "item_cost_history")
public class ItemCostHistory {

    @Id
    private String itemCostHistoryId;

    private String projectId;
    private String itemId;
    private BigDecimal previousUnitCost;
    private BigDecimal unitCost;
    private String changedBy;
    private OffsetDateTime changedAt;
}
