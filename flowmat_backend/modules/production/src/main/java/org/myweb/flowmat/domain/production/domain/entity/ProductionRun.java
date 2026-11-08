package org.myweb.flowmat.domain.production.domain.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.Setter;
import org.myweb.flowmat.global.common.CreatedUpdatedAuditEntity;

@Getter
@Setter
@Entity
@Table(name = "production_run")
public class ProductionRun extends CreatedUpdatedAuditEntity {

    @Id
    private String productionRunId;

    private String projectId;
    private String workflowId;
    private String workflowRevisionId;
    private String workOrderId;
    private String bomId;

    /** BOM snapshot fixed at start: which revision, and its base quantity at that moment. */
    private Integer bomVersion;
    private BigDecimal bomBaseQuantity;
    private String runNumber;
    private String runType;
    private String runStatus;
    private String targetItemId;
    /** The first successfully confirmed instruction revision, retained even after all checks are undone. */
    private String workInstructionId;
    private BigDecimal plannedOutputQty;
    private BigDecimal actualOutputQty;
    private String startedBy;
    private String finishedBy;

    /** Existing V1 column. The original end instant prices finished runs and must remain unchanged by corrections. */
    private OffsetDateTime actualEndAt;

    /** Legacy run types were physical; only the explicitly non-physical types are isolated. */
    public boolean affectsPhysicalState() {
        return !"simulation".equalsIgnoreCase(runType)
            && !"test".equalsIgnoreCase(runType)
            && !"dry_run".equalsIgnoreCase(runType);
    }
}
