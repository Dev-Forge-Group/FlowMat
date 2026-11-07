package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.BaseTimeEntity;

/** A traceable batch of one item. lot_no is unique per project (V17). */
@Getter
@Setter
@Entity
@Table(name = "lot_master")
public class LotMaster extends BaseTimeEntity {

    @Id
    private String lotId;

    private String projectId;
    private String itemId;
    private String inventoryId;

    /** The run that produced this LOT, when it came from production. */
    private String productionRunId;
    private String lotNo;
    private String serialNo;
    private OffsetDateTime producedAt;
    private OffsetDateTime receivedAt;
    private LocalDate expiryDate;

    /** {@link org.myweb.flowmat.domain.inventory.domain.enums.LotStatus} code. */
    private String lotStatus;

    /**
     * Past its expiry date: usable through the expiry date itself, expired from the next day (docs/domain/lot-expiry.md).
     * Expiry is worked out from the date, never stored as a status.
     */
    public boolean isExpiredOn(LocalDate today) {
        return expiryDate != null && expiryDate.isBefore(today);
    }
}
