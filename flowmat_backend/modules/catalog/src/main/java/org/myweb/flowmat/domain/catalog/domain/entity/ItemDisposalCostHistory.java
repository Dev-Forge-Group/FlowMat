package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;

/** One change of an item's disposal cost (docs/domain/bom-by-products.md WD2); null is unknown, zero is free. */
@Getter
@Setter
@Entity
@Immutable
@Table(name = "item_disposal_cost_history")
public class ItemDisposalCostHistory {
    @Id private String itemDisposalCostHistoryId;
    private String projectId;
    private String itemId;
    private BigDecimal previousDisposalCost;
    private BigDecimal disposalCost;
    private String changedBy;
    private OffsetDateTime changedAt;
}
