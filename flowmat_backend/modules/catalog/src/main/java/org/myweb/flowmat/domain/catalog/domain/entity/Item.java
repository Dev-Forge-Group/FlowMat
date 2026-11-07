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
@Table(name = "item")
public class Item extends CreatedUpdatedAuditEntity {

    @Id
    private String itemId;

    private String projectId;
    private String itemCode;
    private String itemName;
    private String itemType;
    private String resourceCategory;
    private String resourceType;
    private String unitId;
    private String itemStatus;

    /** "Y" when stock of this item is tracked per LOT (docs/domain/inventory-bom-lot-contract.md §6). */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String lotManageYn;

    /**
     * "Y" when a new LOT of this item waits for its receipt checks before its stock can be used (docs/domain/lot-release.md).
     * "N" unless set, like the column, so items created without it keep their LOTs available as before.
     */
    @JdbcTypeCode(SqlTypes.CHAR)
    private String lotReleaseRequiredYn = "N";

    /**
     * Stock to keep on hand across all of the item's records; below it the item is on the reorder list
     * (docs/domain/stock-alert.md "재주문 목록"). Null or 0 means not watched.
     */
    private BigDecimal safetyStockQty;

    /** Days from ordering to receiving, shown with the reorder list. */
    private Integer leadTimeDays;

    /**
     * Cost of one unit of the item in its own unit, used for BOM material cost and stock value
     * (docs/domain/material-cost.md). 0 or null means not known.
     */
    private BigDecimal unitCost;

    /** Free grouping for lists and reports, e.g. "flour" or "packaging" (docs/domain/item-details.md). */
    private String itemGroup;
    private String spec;
    /** Unique among the project's active items when set. */
    private String barcode;
    private String sku;
    private String storageCondition;
    private String itemDesc;

    /**
     * What the item is bought in, e.g. "bag", and how many stock units one holds (the V1 conversion_rate, 1 by default)
     * (docs/domain/item-details.md "구매 단위"). Null when it is bought in its stock unit.
     */
    private String purchaseUnit;

    @Column(name = "conversion_rate", precision = 18, scale = 8)
    private BigDecimal conversionRate;
}
