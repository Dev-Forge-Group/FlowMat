package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * One closing or reopening of a LOT (docs/domain/lot-release.md R9): neither moves a ledger row when the LOT holds nothing,
 * so it is kept here. Rows are only added.
 */
@Getter
@Setter
@Entity
@Table(name = "lot_status_event")
public class LotStatusEvent {
    public static final String CLOSED = "closed";
    public static final String REOPENED = "reopened";

    @Id
    private String lotStatusEventId;

    private String lotId;
    private String projectId;
    /** closed or reopened. */
    private String action;
    /** The LOT's status before and after. */
    private String fromStatus;
    private String toStatus;
    private String createdBy;
    private OffsetDateTime createdAt;
}
