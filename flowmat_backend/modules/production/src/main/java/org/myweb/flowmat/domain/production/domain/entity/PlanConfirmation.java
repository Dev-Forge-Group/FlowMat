package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;

/** What one plan confirmation made (docs/architecture/adr/ADR-043-plan-backwards-from-a-target.md decision 7). */
@Getter
@Setter
@Entity
@Table(name = "plan_confirmation")
@IdClass(PlanConfirmation.Key.class)
public class PlanConfirmation {

    public record Key(String workflowId, String requestId) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    private String workflowId;
    @Id
    private String requestId;
    private String projectId;
    private String revisionId;
    private String targetPortId;
    private String targetConnectionId;
    private BigDecimal targetQuantity;
    private String workOrderId;
    private String purchaseOrderId;
    private String createdBy;
    private OffsetDateTime createdAt;
}
