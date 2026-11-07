package org.myweb.flowmat.domain.catalog.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** What disposing of one unit of an item costs now (docs/domain/bom-by-products.md WD1); not its unit cost. */
@Getter
@Setter
@Entity
@Table(name = "item_disposal_cost")
public class ItemDisposalCost {
    @Id private String itemId;
    @Column(precision = 14, scale = 4)
    private BigDecimal disposalCost;
    private long version;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
