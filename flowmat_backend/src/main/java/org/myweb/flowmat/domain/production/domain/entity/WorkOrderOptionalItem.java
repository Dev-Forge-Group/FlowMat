package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** An optional BOM material a work order takes, by item (docs/architecture/adr/ADR-033-bom-optional-materials.md, V133). */
@Getter
@Setter
@Entity
@Table(name = "work_order_optional_item")
public class WorkOrderOptionalItem {
    @Id
    private String workOrderOptionalItemId;
    /** The order; the row goes with it. */
    private String workOrderId;
    private String itemId;
    private String createdBy;
    private OffsetDateTime createdAt;
}
