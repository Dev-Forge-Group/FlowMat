package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A stock row outside its thresholds (docs/domain/stock-alert.md). At most one open alert per row and type; it closes
 * by itself when the stock is back inside the threshold.
 */
@Getter
@Setter
@Entity
@Table(name = "stock_alert")
public class StockAlert {

    public static final String LOW = "low";
    public static final String OVER = "over";
    public static final String EXPIRY = "expiry";

    @Id
    private String stockAlertId;

    private String projectId;
    private String inventoryId;
    private String itemId;

    /**
     * {@link #LOW}: available below the minimum. {@link #OVER}: on hand above the maximum. {@link #EXPIRY}: stock of a LOT
     * that expires within the warning window or already has (threshold = window in days, actual = days left).
     */
    private String alertType;

    /** critical (nothing available), warning or info. */
    private String severity;
    private BigDecimal thresholdValue;

    /** The latest value while the alert is open. */
    private BigDecimal actualValue;
    private String alertMessage;

    @JdbcTypeCode(SqlTypes.CHAR)
    private String resolvedYn;
    private OffsetDateTime resolvedAt;
    private String resolvedBy;
    private OffsetDateTime triggeredAt;
    private OffsetDateTime createdAt;

    public boolean isResolved() {
        return "Y".equals(resolvedYn);
    }
}
