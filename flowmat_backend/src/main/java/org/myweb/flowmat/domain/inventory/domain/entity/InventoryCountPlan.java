package org.myweb.flowmat.domain.inventory.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "inventory_count_plan")
public class InventoryCountPlan {
    @Id private String planId;
    private String projectId;
    private UUID requestId;
    private boolean blind;
    private String status;
    @Column(length = 500)
    private String note;
    private String createdBy;
    private OffsetDateTime createdAt;
    private String submittedBy;
    private OffsetDateTime submittedAt;
    private String countId;
    /** Who should count it (P9); null when no one is. */
    private String assignedTo;
}
