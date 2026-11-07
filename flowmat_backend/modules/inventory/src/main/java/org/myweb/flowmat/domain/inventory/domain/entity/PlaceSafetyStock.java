package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * An item's safety stock at one place of the location list (docs/domain/stock-alert.md PS1): the place's reorder list
 * watches the item against the usable stock there and in the places inside it.
 */
@Getter
@Setter
@Entity
@Table(name = "place_safety_stock")
public class PlaceSafetyStock {

    @Id
    private String placeSafetyStockId;

    private String projectId;
    private String itemId;
    private String locationId;

    @Column(precision = 14, scale = 4)
    private BigDecimal safetyStockQty;

    private String createdBy;
    private OffsetDateTime createdAt;
    private String updatedBy;
    private OffsetDateTime updatedAt;
}
